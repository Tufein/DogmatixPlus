package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebDavXmlTest {

    /** Nextcloud 30, `PROPFIND Depth: 1` of the backups folder (d:, oc:, nc: prefixes; 404 propstats). */
    private val nextcloud = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
         <d:response>
          <d:href>/remote.php/dav/files/john%40example.com/Dogmatix/backups/</d:href>
          <d:propstat>
           <d:prop>
            <d:resourcetype><d:collection/></d:resourcetype>
            <d:getlastmodified>Sun, 04 Oct 2026 21:14:02 GMT</d:getlastmodified>
            <d:getetag>&quot;6514f0a2c1d3e&quot;</d:getetag>
           </d:prop>
           <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
          <d:propstat>
           <d:prop><d:getcontentlength/><d:getcontenttype/></d:prop>
           <d:status>HTTP/1.1 404 Not Found</d:status>
          </d:propstat>
         </d:response>
         <d:response>
          <d:href>/remote.php/dav/files/john%40example.com/Dogmatix/backups/dogmatix-20261004-2114-retroid-pocket-5.dgxb</d:href>
          <d:propstat>
           <d:prop>
            <d:resourcetype/>
            <d:getcontentlength>48213</d:getcontentlength>
            <d:getlastmodified>Sun, 04 Oct 2026 21:14:02 GMT</d:getlastmodified>
            <d:getetag>&quot;a1b2c3&quot;</d:getetag>
            <d:getcontenttype>application/octet-stream</d:getcontenttype>
            <oc:size>48213</oc:size>
           </d:prop>
           <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
         </d:response>
         <d:response>
          <d:href>/remote.php/dav/files/john%40example.com/Dogmatix/backups/My%20Backup%20%C3%A9+1.dgxb</d:href>
          <d:propstat>
           <d:prop><d:resourcetype/><d:getcontentlength>10</d:getcontentlength></d:prop>
           <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
         </d:response>
         <d:response>
          <d:href>/remote.php/dav/files/john%40example.com/Dogmatix/backups/old/</d:href>
          <d:propstat>
           <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
           <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
         </d:response>
        </d:multistatus>
    """.trimIndent()

    /** Apache mod_dav: `D:` for the structure, `lp1:` (also DAV:) for live properties. */
    private val apache = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
        <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
        <D:href>/dav/Dogmatix/sync/</D:href>
        <D:propstat>
        <D:prop>
        <lp1:resourcetype><D:collection/></lp1:resourcetype>
        <lp1:creationdate>2026-10-04T21:14:02Z</lp1:creationdate>
        <lp1:getlastmodified>Sun, 04 Oct 2026 21:14:02 GMT</lp1:getlastmodified>
        <lp1:getetag>"1000-5f0b3a"</lp1:getetag>
        <D:getcontenttype>httpd/unix-directory</D:getcontenttype>
        </D:prop>
        <D:status>HTTP/1.1 200 OK</D:status>
        </D:propstat>
        </D:response>
        <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
        <D:href>/dav/Dogmatix/sync/library.json</D:href>
        <D:propstat>
        <D:prop>
        <lp1:resourcetype/>
        <lp1:getcontentlength>2048</lp1:getcontentlength>
        <lp1:getlastmodified>Mon, 05 Oct 2026 08:00:00 GMT</lp1:getlastmodified>
        <lp1:getetag>"800-5f0b3b"</lp1:getetag>
        <lp2:executable>F</lp2:executable>
        <D:getcontenttype>application/json</D:getcontenttype>
        </D:prop>
        <D:status>HTTP/1.1 200 OK</D:status>
        </D:propstat>
        </D:response>
        </D:multistatus>
    """.trimIndent()

    /** Default namespace and absolute hrefs (IIS, some NAS firmwares). */
    private val defaultNamespace = """<?xml version="1.0" encoding="UTF-8"?>
        <multistatus xmlns="DAV:"><response><href>https://nas.local:5006/home/Dogmatix/</href>
        <propstat><status>HTTP/1.1 200 OK</status><prop><resourcetype><collection/></resourcetype></prop></propstat></response>
        <response><href>https://nas.local:5006/home/Dogmatix/notes%2Etxt</href>
        <propstat><prop><getcontentlength>12</getcontentlength><resourcetype/><getlastmodified>Mon, 05 Oct 2026 08:00:00 GMT</getlastmodified></prop><status>HTTP/1.1 200 OK</status></propstat></response>
        <response><href>https://nas.local:5006/home/Dogmatix/gone.txt</href><status>HTTP/1.1 404 Not Found</status></response>
        </multistatus>"""

    @Test fun `reads a Nextcloud listing with its 404 propstats`() {
        val entries = WebDavXml.parse(nextcloud)
        assertEquals(4, entries.size)
        val folder = entries[0]
        assertTrue(folder.isCollection)
        assertEquals("/remote.php/dav/files/john@example.com/Dogmatix/backups", folder.path)
        assertNull(folder.size)
        assertEquals("\"6514f0a2c1d3e\"", folder.etag)
        val file = entries[1]
        assertFalse(file.isCollection)
        assertEquals("dogmatix-20261004-2114-retroid-pocket-5.dgxb", file.name)
        assertEquals(48213L, file.size)
        assertEquals(1791148442000L, file.lastModified)
        assertEquals("application/octet-stream", file.contentType)
    }

    @Test fun `decodes hrefs but keeps a plus sign`() {
        val entries = WebDavXml.parse(nextcloud)
        assertEquals("My Backup é+1.dgxb", entries[2].name)
        assertTrue(entries[3].isCollection)
        assertEquals("old", entries[3].name)
    }

    @Test fun `matches elements by namespace not by prefix`() {
        val entries = WebDavXml.parse(apache)
        assertEquals(2, entries.size)
        assertTrue(entries[0].isCollection)
        val json = entries[1]
        assertEquals("library.json", json.name)
        assertEquals(2048L, json.size)
        assertEquals("\"800-5f0b3b\"", json.etag)
        assertFalse(json.isCollection)
    }

    @Test fun `reads a default namespace with absolute hrefs and skips 404 members`() {
        val entries = WebDavXml.parse(defaultNamespace)
        assertEquals(listOf("/home/Dogmatix", "/home/Dogmatix/notes.txt"), entries.map { it.path })
        assertEquals(12L, entries[1].size)
        assertTrue(entries[0].isCollection)
    }

    @Test fun `accepts a document without any namespace and a byte order mark`() {
        val xml = "﻿<multistatus><response><href>/dav/a/</href><propstat><prop><resourcetype><collection/></resourcetype></prop>" +
            "<status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"
        val entries = WebDavXml.parse(xml)
        assertEquals(1, entries.size)
        assertTrue(entries[0].isCollection)
    }

    @Test fun `guesses a collection from a trailing slash when resourcetype is missing`() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/x/folder/</d:href></d:response>
            <d:response><d:href>/x/file.bin</d:href><d:propstat><d:prop><d:getcontentlength>5</d:getcontentlength></d:prop></d:propstat></d:response></d:multistatus>"""
        val entries = WebDavXml.parse(xml)
        assertTrue(entries[0].isCollection)
        assertFalse(entries[1].isCollection)
        assertEquals(5L, entries[1].size)
    }

    @Test fun `members leave the folder itself out whatever its encoding`() {
        val entries = WebDavXml.parse(nextcloud)
        val members = WebDavXml.membersOf(entries, "https://cloud.example.com/remote.php/dav/files/john@example.com/Dogmatix/backups/")
        assertEquals(3, members.size)
        assertFalse(members.any { it.name == "backups" })
        val self = WebDavXml.selfOf(entries, "https://cloud.example.com/remote.php/dav/files/john%40example.com/Dogmatix/backups")
        assertEquals("backups", self?.name)
    }

    @Test fun `a web page is not a multistatus`() {
        try {
            WebDavXml.parse("<!DOCTYPE html><html><head><title>Nextcloud</title></head><body>Login</body></html>")
            fail("expected NotMultistatusException")
        } catch (_: WebDavXml.NotMultistatusException) {
        }
        try {
            WebDavXml.parse("<html><body>It works!</body></html>")
            fail("expected NotMultistatusException")
        } catch (_: WebDavXml.NotMultistatusException) {
        }
        try {
            WebDavXml.parse("")
            fail("expected NotMultistatusException")
        } catch (_: WebDavXml.NotMultistatusException) {
        }
    }

    @Test fun `refuses a document type declaration with external entities`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE d:multistatus [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>&x;</d:href></d:response></d:multistatus>"""
        try {
            val entries = WebDavXml.parse(xxe)
            // A parser that ignores the DOCTYPE must at least not have read the file.
            assertFalse(entries.any { it.href.contains("root:") })
        } catch (_: WebDavXml.NotMultistatusException) {
        }
    }

    @Test fun `reads status lines and dates`() {
        assertEquals(200, WebDavXml.statusCode("HTTP/1.1 200 OK"))
        assertEquals(404, WebDavXml.statusCode(" HTTP/2 404 Not Found "))
        assertNull(WebDavXml.statusCode("weird"))
        assertEquals(1791148442000L, WebDavXml.parseDate("Sun, 04 Oct 2026 21:14:02 GMT"))
        assertEquals(1791148442000L, WebDavXml.parseDate("2026-10-04T21:14:02Z"))
        assertEquals(1791148442000L, WebDavXml.parseDate("2026-10-04T23:14:02+02:00"))
        assertNull(WebDavXml.parseDate("yesterday"))
        assertNull(WebDavXml.parseDate(null))
    }

    @Test fun `the propfind body asks the properties in the DAV namespace`() {
        assertTrue(WebDavXml.PROPFIND_BODY.contains("xmlns:d=\"DAV:\""))
        assertTrue(WebDavXml.PROPFIND_BODY.contains("<d:getetag/>"))
    }
}
