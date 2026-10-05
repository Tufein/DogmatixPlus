package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavPathsTest {

    @Test fun `normalizes what the user typed`() {
        assertEquals("https://cloud.example.com/", WebDavPaths.normalizeServer("cloud.example.com"))
        assertEquals("https://cloud.example.com/remote.php/dav/files/John/", WebDavPaths.normalizeServer("  https://Cloud.Example.com/remote.php/dav/files/John "))
        assertEquals("http://192.168.1.20:8080/dav/", WebDavPaths.normalizeServer("http://192.168.1.20:8080/dav?x=1#top"))
        assertEquals("https://nas.local:5006/home/", WebDavPaths.normalizeServer("https://nas.local:5006//home//"))
        assertNull(WebDavPaths.normalizeServer(""))
        assertNull(WebDavPaths.normalizeServer("ftp://files.example.com/"))
        assertNull(WebDavPaths.normalizeServer("https://john:secret@cloud.example.com/"))
        assertNull(WebDavPaths.normalizeServer("https://"))
        assertNull(WebDavPaths.normalizeServer("https://a\tb/"))
    }

    @Test fun `tries the Nextcloud files address after the server alone`() {
        assertEquals(
            listOf("https://cloud.example.com/", "https://cloud.example.com/remote.php/dav/files/john/"),
            WebDavPaths.candidates("https://cloud.example.com", "john")
        )
        assertEquals(
            listOf("https://example.com/nextcloud/", "https://example.com/nextcloud/remote.php/dav/files/john/"),
            WebDavPaths.candidates("example.com/nextcloud", "john")
        )
    }

    @Test fun `keeps a full files address as it is`() {
        assertEquals(
            listOf("https://cloud.example.com/remote.php/dav/files/john/"),
            WebDavPaths.candidates("https://cloud.example.com/remote.php/dav/files/john", "john")
        )
    }

    @Test fun `turns the dav root and the old webdav address into the files address`() {
        assertEquals(
            listOf("https://cloud.example.com/remote.php/dav/files/john/", "https://cloud.example.com/remote.php/webdav/"),
            WebDavPaths.candidates("https://cloud.example.com/remote.php/dav/", "john")
        )
        assertEquals(
            listOf("https://cloud.example.com/remote.php/webdav/", "https://cloud.example.com/remote.php/dav/files/john/"),
            WebDavPaths.candidates("https://cloud.example.com/remote.php/webdav", "john")
        )
    }

    @Test fun `a link from the web interface leads to the files address`() {
        assertEquals(
            "https://cloud.example.com/sub/remote.php/dav/files/john/",
            WebDavPaths.candidates("https://cloud.example.com/sub/index.php/apps/files/?dir=/Games", "john").first()
        )
    }

    @Test fun `encodes the user name in the files address`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/john%40example.com/",
            WebDavPaths.candidates("https://cloud.example.com", "john@example.com")[1]
        )
        assertEquals(listOf("https://cloud.example.com/"), WebDavPaths.candidates("https://cloud.example.com", " "))
        assertTrue(WebDavPaths.candidates("not a url\n", "john").isEmpty())
    }

    @Test fun `builds the app folder with encoded names`() {
        assertEquals("https://nas.local:5006/home/Dogmatix/", WebDavPaths.folderUrl("https://nas.local:5006/home", ""))
        assertEquals("https://nas.local:5006/home/Games/Dogmatix/", WebDavPaths.folderUrl("https://nas.local:5006/home/", "/Games/Dogmatix/"))
        assertEquals("https://h/My%20Games/Caf%C3%A9/", WebDavPaths.folderUrl("https://h", "My Games\\Café"))
        assertEquals("https://h/Dogmatix/", WebDavPaths.folderUrl("https://h", "../."))
        assertNull(WebDavPaths.folderUrl("", "Dogmatix"))
        assertEquals("https://h/a/b%23c.json", WebDavPaths.child("https://h/a/", "b#c.json"))
        assertEquals("https://h/a/sync/", WebDavPaths.childCollection("https://h/a", "sync"))
    }

    @Test fun `encodes and decodes segments`() {
        assertEquals("a%20b%2Bc%2F%C3%A9~_.-", WebDavPaths.encodeSegment("a b+c/é~_.-"))
        assertEquals("a b+cé", WebDavPaths.percentDecode("a%20b+c%C3%A9"))
        assertEquals("100%zz%", WebDavPaths.percentDecode("100%zz%"))
        assertEquals("x%2", WebDavPaths.percentDecode("x%2"))
    }

    @Test fun `compares hrefs and urls by decoded path`() {
        assertEquals(WebDavPaths.pathKey("https://h:8443/dav/a%20b/"), WebDavPaths.pathKey("/dav/a b"))
        assertEquals("/", WebDavPaths.pathKey("https://h"))
        assertEquals("/dav/x", WebDavPaths.pathKey("/dav//x/?y=1"))
        assertEquals("library.json", WebDavPaths.nameOf("https://h/dav/sync/library.json"))
    }

    @Test fun `finds the parent folder`() {
        assertEquals("https://h/a/", WebDavPaths.parentOf("https://h/a/b/"))
        assertEquals("https://h/a/", WebDavPaths.parentOf("https://h/a/f.txt"))
        assertEquals("https://h/", WebDavPaths.parentOf("https://h/a/"))
        assertNull(WebDavPaths.parentOf("https://h/"))
    }

    @Test fun `tells hosts and schemes`() {
        assertEquals("cloud.example.com", WebDavPaths.hostOf("https://Cloud.Example.com:443/x"))
        assertEquals("https://h:8443", WebDavPaths.originOf("https://h:8443/a/b/"))
        assertTrue(WebDavPaths.isHttps(" HTTPS://h/"))
        assertFalse(WebDavPaths.isHttps("http://h/"))
    }

    @Test fun `a login in the address is dropped`() {
        assertEquals("https://cloud.example.com/dav", WebDavPaths.stripCredentials("https://john:secret@cloud.example.com/dav"))
        assertEquals("http://nas.local:5005/", WebDavPaths.stripCredentials(" http://john@nas.local:5005/ "))
        assertEquals("cloud.example.com/dav", WebDavPaths.stripCredentials("john:pw@cloud.example.com/dav"))
        assertEquals("https://cloud.example.com/a@b", WebDavPaths.stripCredentials("https://cloud.example.com/a@b"))
        assertEquals("cloud.example.com", WebDavPaths.stripCredentials("cloud.example.com"))
    }

    @Test fun `plain http is only a risk away from the home network`() {
        assertTrue(WebDavPaths.isCleartextRisk("http://cloud.example.com/dav"))
        assertTrue(WebDavPaths.isCleartextRisk("http://8.8.8.8/"))
        assertTrue(WebDavPaths.isCleartextRisk("http://172.32.0.1/"))
        assertTrue(WebDavPaths.isCleartextRisk("http://203.0.113.5:8080/dav"))
        assertTrue(WebDavPaths.isCleartextRisk("http://john:pw@cloud.example.com/"))
        assertFalse(WebDavPaths.isCleartextRisk("https://cloud.example.com/dav"))
        assertFalse(WebDavPaths.isCleartextRisk("cloud.example.com"))               // https is assumed
        assertFalse(WebDavPaths.isCleartextRisk("http://localhost:8080/"))
        assertFalse(WebDavPaths.isCleartextRisk("http://10.0.0.5/dav"))
        assertFalse(WebDavPaths.isCleartextRisk("http://172.16.4.1/"))
        assertFalse(WebDavPaths.isCleartextRisk("http://172.31.255.1/"))
        assertFalse(WebDavPaths.isCleartextRisk("http://192.168.1.20:8080/dav"))
        assertFalse(WebDavPaths.isCleartextRisk("http://169.254.10.10/"))
        assertFalse(WebDavPaths.isCleartextRisk("http://127.0.0.1:5000/"))
        assertFalse(WebDavPaths.isCleartextRisk("http://nas.local/dav"))
        assertFalse(WebDavPaths.isCleartextRisk("http://synology/dav"))
        assertFalse(WebDavPaths.isCleartextRisk("http://[fe80::1]/"))
        assertFalse(WebDavPaths.isCleartextRisk(""))
    }

    @Test fun `a refused MKCOL is told from an existing folder`() {
        assertEquals(WebDavStatus.MkcolAnswer.CREATED, WebDavStatus.mkcolAnswer(201))
        assertEquals(WebDavStatus.MkcolAnswer.CREATED, WebDavStatus.mkcolAnswer(204))
        assertEquals(WebDavStatus.MkcolAnswer.NOT_ALLOWED, WebDavStatus.mkcolAnswer(405))
        assertEquals(WebDavStatus.MkcolAnswer.FAILED, WebDavStatus.mkcolAnswer(409))
        assertEquals(WebDavStatus.MkcolAnswer.FAILED, WebDavStatus.mkcolAnswer(403))
    }
}
