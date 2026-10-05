package com.cortinadev.dogmatix.util

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the `207 Multi-Status` answer of a WebDAV `PROPFIND` and builds its request body.
 *
 * Servers differ a lot in how they write it: Nextcloud uses `d:`, Apache `D:` and `lp1:`, IIS a
 * default namespace, some nothing at all. So elements are matched by namespace URI (`DAV:`) and
 * local name, never by prefix; properties come only from `propstat` blocks whose status is 2xx
 * (Nextcloud lists the ones it does not have under a `404` propstat); hrefs may be absolute URLs
 * or paths and are percent-decoded. Uses the namespace-aware DOM parser that exists both on
 * Android and in a plain JVM. Pure JVM for the tests.
 */
object WebDavXml {

    private const val DAV = "DAV:"

    /** One resource of a listing. */
    data class Entry(
        /** The href as the server sent it. */
        val href: String,
        /** Decoded path without a trailing slash (see [WebDavPaths.pathKey]). */
        val path: String,
        /** Decoded last name ("" for the root). */
        val name: String,
        val isCollection: Boolean,
        val size: Long? = null,
        /** Epoch milliseconds, when the server said. */
        val lastModified: Long? = null,
        val etag: String? = null,
        val contentType: String? = null
    )

    /** The `PROPFIND` body asking for the properties [Entry] needs. */
    const val PROPFIND_BODY: String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/><d:getcontenttype/>" +
            "</d:prop></d:propfind>"

    /** Not a multistatus document (a web page, an error page, garbage). */
    class NotMultistatusException(message: String) : IllegalArgumentException(message)

    /**
     * Every resource in [xml]. Responses with a non-2xx status of their own (a member that was
     * just deleted) are left out. Throws [NotMultistatusException] when [xml] is not a
     * multistatus document.
     */
    fun parse(xml: String): List<Entry> {
        val root = runCatching { parseDocument(xml) }.getOrElse { throw NotMultistatusException("Not XML: ${it.message}") }
        if (!isDav(root, "multistatus")) throw NotMultistatusException("Root element is <${root.localName ?: root.tagName}>")
        return children(root, "response").mapNotNull(::entryOf)
    }

    /** The members of the collection at [collectionUrl] in a `Depth: 1` listing (the collection itself left out). */
    fun membersOf(entries: List<Entry>, collectionUrl: String): List<Entry> {
        val self = WebDavPaths.pathKey(collectionUrl)
        return entries.filter { it.path != self }
    }

    /** The entry for [url] itself in a listing (`Depth: 0` answers hold only that one). */
    fun selfOf(entries: List<Entry>, url: String): Entry? {
        val self = WebDavPaths.pathKey(url)
        return entries.firstOrNull { it.path == self } ?: entries.singleOrNull()
    }

    /** `HTTP/1.1 404 Not Found` → 404; null when unreadable. */
    fun statusCode(line: String?): Int? =
        line?.let { Regex("""HTTP/\d+(?:\.\d+)?\s+(\d{3})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * `getlastmodified` (RFC 1123, `Sat, 04 Oct 2026 10:00:00 GMT`) — or ISO 8601, which a few
     * servers send — to epoch milliseconds; null when unreadable.
     */
    fun parseDate(text: String?): Long? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
    }

    private fun entryOf(response: Element): Entry? {
        val href = children(response, "href").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A status directly on the response (instead of propstats) is how 404 members are reported.
        val ownStatus = statusCode(children(response, "status").firstOrNull()?.textContent)
        if (ownStatus != null && ownStatus !in 200..299) return null

        var collection: Boolean? = null
        var size: Long? = null
        var modified: Long? = null
        var etag: String? = null
        var type: String? = null
        for (propstat in children(response, "propstat")) {
            val status = statusCode(children(propstat, "status").firstOrNull()?.textContent)
            if (status != null && status !in 200..299) continue
            for (prop in children(propstat, "prop")) {
                children(prop, "resourcetype").firstOrNull()?.let { rt -> collection = children(rt, "collection").isNotEmpty() }
                children(prop, "getcontentlength").firstOrNull()?.textContent?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { size = it }
                children(prop, "getlastmodified").firstOrNull()?.textContent?.let { parseDate(it) }?.let { modified = it }
                children(prop, "getetag").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { etag = it }
                children(prop, "getcontenttype").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { type = it }
            }
        }
        val path = WebDavPaths.pathKey(href)
        return Entry(
            href = href,
            path = path,
            name = path.substringAfterLast('/'),
            // Without a resourcetype, a trailing slash is the usual sign of a collection.
            isCollection = collection ?: (href.endsWith("/") || type?.startsWith("httpd/unix-directory") == true),
            size = size,
            lastModified = modified,
            etag = etag,
            contentType = type
        )
    }

    private fun parseDocument(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(true)
        factory.setExpandEntityReferences(false)
        // No DOCTYPE, no external entities (XXE). Android's parser rejects unknown features; it
        // does not resolve external entities anyway.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val builder = factory.newDocumentBuilder()
        // Quiet: the default handler prints parse errors to stderr before throwing.
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException) { throw exception }
            override fun fatalError(exception: SAXParseException) { throw exception }
        })
        // A BOM or blank lines before the declaration make some parsers fail.
        val text = xml.trimStart('﻿', ' ', '\n', '\r', '\t')
        return builder.parse(InputSource(StringReader(text))).documentElement
    }

    /** Element children of [parent] named `DAV:`[name] (also accepted without any namespace). */
    private fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && isDav(node as Element, name)) out += node
        }
        return out
    }

    private fun isDav(element: Element, name: String): Boolean {
        val local = element.localName ?: element.tagName.substringAfter(':')
        if (!local.equals(name, ignoreCase = true)) return false
        val ns = element.namespaceURI
        return ns == null || ns.isEmpty() || ns.equals(DAV, ignoreCase = true)
    }
}
