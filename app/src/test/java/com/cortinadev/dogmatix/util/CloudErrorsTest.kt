package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.service.BackupService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CloudErrorsTest {

    private fun roundTrip(e: Throwable) = CloudErrors.decode(CloudErrors.encode(e))

    @Test fun `every webdav problem survives a round trip`() {
        DavProblem.entries.forEach { problem ->
            val decoded = roundTrip(DavException(problem, 418, "extra")) as CloudErrors.Decoded.Dav
            assertEquals(problem, decoded.problem)
            assertEquals(418, decoded.code)
            assertEquals("extra", decoded.detail)
        }
    }

    @Test fun `a problem without code or detail decodes to zero and null`() {
        val decoded = roundTrip(DavException(DavProblem.NETWORK)) as CloudErrors.Decoded.Dav
        assertEquals(0, decoded.code)
        assertNull(decoded.detail)
    }

    @Test fun `a separator inside the detail cannot break the code`() {
        val decoded = roundTrip(DavException(DavProblem.REDIRECT, 301, "https://a.example/|b|c")) as CloudErrors.Decoded.Dav
        assertEquals(DavProblem.REDIRECT, decoded.problem)
        assertEquals(301, decoded.code)
        assertEquals("https://a.example/ b c", decoded.detail)
    }

    @Test fun `the known failures map to their kind`() {
        val cases = mapOf<Throwable, CloudErrors.Kind>(
            BackupCrypto.UnreadableException() to CloudErrors.Kind.WRONG_PASSPHRASE,
            BackupCrypto.NotEncryptedException() to CloudErrors.Kind.NOT_A_BACKUP,
            BackupService.InvalidBackupException() to CloudErrors.Kind.INVALID_BACKUP,
            BackupService.NewerBackupException(9) to CloudErrors.Kind.NEWER_BACKUP,
            DeviceSyncEngine.NewerFileException(9) to CloudErrors.Kind.SYNC_NEWER,
            DeviceSyncEngine.UnreadableFileException() to CloudErrors.Kind.SYNC_UNREADABLE,
            CloudNotConfiguredException() to CloudErrors.Kind.NOT_CONFIGURED,
            CloudNoPassphraseException() to CloudErrors.Kind.NO_PASSPHRASE,
            CloudBackupTooLargeException(9_000_000, 8_388_608) to CloudErrors.Kind.BACKUP_TOO_LARGE
        )
        cases.forEach { (error, kind) ->
            assertEquals(error.javaClass.simpleName, CloudErrors.Decoded.Known(kind), roundTrip(error))
        }
        assertEquals(CloudErrors.Kind.entries.toSet(), cases.values.toSet())
    }

    @Test fun `an unknown failure keeps a short one line text`() {
        val decoded = roundTrip(IOException("disk\nfull " + "x".repeat(500))) as CloudErrors.Decoded.Other
        assertFalse(decoded.text.contains('\n'))
        assertTrue(decoded.text.length <= 120)
        assertTrue(decoded.text.startsWith("disk full"))
        assertEquals(IllegalStateException().javaClass.simpleName, (roundTrip(IllegalStateException()) as CloudErrors.Decoded.Other).text)
    }

    @Test fun `no error is no code`() {
        assertNull(CloudErrors.decode(""))
        assertNull(CloudErrors.decode("   "))
    }

    @Test fun `garbled or old stored text never crashes`() {
        assertEquals(CloudErrors.Decoded.Other("dav|NO_SUCH_PROBLEM|1|x"), CloudErrors.decode("dav|NO_SUCH_PROBLEM|1|x"))
        assertEquals(CloudErrors.Decoded.Other("k|NOPE"), CloudErrors.decode("k|NOPE"))
        assertEquals(CloudErrors.Decoded.Other("Connection refused"), CloudErrors.decode("Connection refused"))
        assertNotNull(CloudErrors.decode("dav"))
        assertNotNull(CloudErrors.decode("k"))
        val noCode = CloudErrors.decode("dav|AUTH") as CloudErrors.Decoded.Dav
        assertEquals(DavProblem.AUTH, noCode.problem)
        assertEquals(0, noCode.code)
    }

    @Test fun `a code never carries the exception's secret text`() {
        // Only a DavException's own fields travel; its message (which could name a host) does not.
        val encoded = CloudErrors.encode(DavException(DavProblem.AUTH, 401))
        assertEquals("dav|AUTH|401|", encoded)
    }

    @Test fun `connection problems are the ones that make a passed test stale`() {
        listOf(DavProblem.AUTH, DavProblem.DIGEST_ONLY, DavProblem.NOT_WEBDAV, DavProblem.TLS, DavProblem.REDIRECT, DavProblem.BAD_URL)
            .forEach { assertTrue(it.name, CloudErrors.isConnectionProblem(DavException(it))) }
        listOf(DavProblem.NETWORK, DavProblem.TIMEOUT, DavProblem.NO_SPACE, DavProblem.PRECONDITION, DavProblem.FORBIDDEN)
            .forEach { assertFalse(it.name, CloudErrors.isConnectionProblem(DavException(it))) }
        assertFalse(CloudErrors.isConnectionProblem(IOException("x")))
    }

    @Test fun `only being offline or slow is transient`() {
        assertTrue(CloudErrors.isTransient(DavException(DavProblem.NETWORK)))
        assertTrue(CloudErrors.isTransient(DavException(DavProblem.TIMEOUT)))
        assertFalse(CloudErrors.isTransient(DavException(DavProblem.AUTH, 401)))
        assertFalse(CloudErrors.isTransient(DavException(DavProblem.NO_SPACE, 507)))
        assertFalse(CloudErrors.isTransient(CloudNoPassphraseException()))
        assertFalse(CloudErrors.isTransient(IOException("x")))
    }
}
