package com.tufein.valkyr.util

import com.google.gson.JsonParser
import com.tufein.valkyr.util.RommRemoteInstall.ClaimFailure
import com.tufein.valkyr.util.RommRemoteInstall.Decision
import com.tufein.valkyr.util.RommRemoteInstall.PairPoll
import com.tufein.valkyr.util.RommRemoteInstall.Report
import com.tufein.valkyr.util.RommRemoteInstall.Row
import com.tufein.valkyr.util.RommRemoteInstall.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RommRemoteInstallTest {

    private fun json(text: String) = JsonParser.parseString(text)

    @Test fun `the heartbeat says whether the server offers remote install`() {
        assertEquals(Support.YES, RommRemoteInstall.support(json("""{"DEVICE_INSTALL":{"ENABLED":true,"EXCLUDED_PLATFORM_SLUGS":[]}}""")))
        assertEquals(Support.NO, RommRemoteInstall.support(json("""{"DEVICE_INSTALL":{"ENABLED":false}}""")))
        // A server from before the feature does not list it.
        assertEquals(Support.UNKNOWN, RommRemoteInstall.support(json("""{"SYSTEM":{"VERSION":"3.0"}}""")))
        assertEquals(Support.UNKNOWN, RommRemoteInstall.support(null))
        assertEquals(Support.UNKNOWN, RommRemoteInstall.support(json("""{"DEVICE_INSTALL":"yes"}""")))
    }

    @Test fun `the init request names the scopes the claim needs`() {
        val body = json(RommRemoteInstall.initBody("valkyr-1", "Odin 2", "5.6.0")).asJsonObject
        assertEquals("valkyr-1", body.get("client_device_identifier").asString)
        assertEquals("valkyr", body.get("client").asString)
        assertEquals("android", body.get("platform").asString)
        val scopes = body.getAsJsonArray("requested_scopes").map { it.asString }
        assertTrue(RommRemoteInstall.scopesSufficient(scopes))
        assertFalse(RommRemoteInstall.scopesSufficient(listOf("me.read", "roms.read")))
        assertFalse(RommRemoteInstall.scopesSufficient(listOf("devices.write")))
    }

    @Test fun `the init answer is read with sane limits`() {
        val init = RommRemoteInstall.parseInit(201, json("""{"device_code":"dc","user_code":"ABCD2345","verification_path":"/pair/device","verification_path_complete":"/pair/device?user_code=ABCD2345","expires_in":600,"interval":5}"""))!!
        assertEquals("dc", init.deviceCode)
        assertEquals("ABCD2345", init.userCode)
        assertEquals("/pair/device?user_code=ABCD2345", init.verificationPath)
        assertEquals(600, init.expiresInSeconds)
        assertEquals(5, init.intervalSeconds)
        val wild = RommRemoteInstall.parseInit(200, json("""{"device_code":"dc","user_code":"U","expires_in":99999999,"interval":0}"""))!!
        assertEquals(3_600, wild.expiresInSeconds)
        assertEquals(2, wild.intervalSeconds)
        assertNull(RommRemoteInstall.parseInit(200, json("""{"user_code":"U"}""")))
        assertNull(RommRemoteInstall.parseInit(429, json("""{"device_code":"dc","user_code":"U"}""")))
        assertNull(RommRemoteInstall.parseInit(200, null))
    }

    @Test fun `the approval address stays on the configured server`() {
        assertEquals("https://romm.lan/pair/device?user_code=AB", RommRemoteInstall.verificationUrl("https://romm.lan/", "/pair/device?user_code=AB"))
        assertNull(RommRemoteInstall.verificationUrl("https://romm.lan", "https://evil.example/x"))
        assertNull(RommRemoteInstall.verificationUrl("https://romm.lan", "//evil.example/x"))
        assertNull(RommRemoteInstall.verificationUrl("https://romm.lan", "pair/device"))
        assertNull(RommRemoteInstall.verificationUrl("https://romm.lan", ""))
        assertNull(RommRemoteInstall.verificationUrl("https://romm.lan", "/a b"))
    }

    @Test fun `the user code is shown in two halves`() {
        assertEquals("ABCD-2345", RommRemoteInstall.displayUserCode("abcd2345"))
        assertEquals("ABC", RommRemoteInstall.displayUserCode("abc"))
    }

    @Test fun `the token poll tells waiting from the end`() {
        val approved = RommRemoteInstall.parsePoll(200, json("""{"access_token":"rmm_x","device_id":"dev-1","scopes":["roms.read","devices.write"],"expires_at":null}"""))
        assertEquals(PairPoll.Approved("rmm_x", "dev-1", listOf("roms.read", "devices.write")), approved)
        assertEquals(PairPoll.Waiting(false), RommRemoteInstall.parsePoll(400, json("""{"detail":"authorization_pending"}""")))
        assertEquals(PairPoll.Waiting(true), RommRemoteInstall.parsePoll(400, json("""{"detail":"slow_down"}""")))
        assertEquals(PairPoll.Waiting(true), RommRemoteInstall.parsePoll(429, null))
        assertEquals(PairPoll.Denied, RommRemoteInstall.parsePoll(400, json("""{"detail":"access_denied"}""")))
        assertEquals(PairPoll.Expired, RommRemoteInstall.parsePoll(400, json("""{"detail":"expired_token"}""")))
        assertEquals(PairPoll.Failed(400), RommRemoteInstall.parsePoll(400, json("""{"detail":"something else"}""")))
        assertEquals(PairPoll.Failed(500), RommRemoteInstall.parsePoll(500, null))
        // An approval without a client token or a device is not one.
        assertEquals(PairPoll.Failed(0), RommRemoteInstall.parsePoll(200, json("""{"access_token":"abc","device_id":"d"}""")))
        assertEquals(PairPoll.Failed(0), RommRemoteInstall.parsePoll(200, json("""{"access_token":"rmm_x"}""")))
    }

    @Test fun `slow down adds five seconds up to a limit`() {
        assertEquals(5, RommRemoteInstall.nextInterval(5, false))
        assertEquals(10, RommRemoteInstall.nextInterval(5, true))
        assertEquals(30, RommRemoteInstall.nextInterval(28, true))
    }

    @Test fun `the device says it takes installs`() {
        val body = json(RommRemoteInstall.capabilitiesBody()).asJsonObject
        assertTrue(body.getAsJsonObject("capabilities").get("remote_install").asBoolean)
    }

    @Test fun `the claim lists the taken requests and skips junk`() {
        val list = RommRemoteInstall.parseClaim(json("""[
            {"id":"r1","rom_id":12,"file_ids":[3,4],"status":"taken"},
            {"id":"r2","rom_id":13,"file_ids":[],"status":"taken"},
            {"id":"r1","rom_id":12,"file_ids":[3,4],"status":"taken"},
            {"id":"r3","rom_id":0,"status":"taken"},
            {"rom_id":14,"status":"taken"},
            {"id":"r4","rom_id":15,"status":"pending"},
            "nonsense"
        ]"""))
        assertEquals(listOf(RommRemoteInstall.Request("r1", 12, listOf(3, 4)), RommRemoteInstall.Request("r2", 13, emptyList())), list)
        assertTrue(RommRemoteInstall.parseClaim(null).isEmpty())
        assertTrue(RommRemoteInstall.parseClaim(json("""{"id":"r1"}""")).isEmpty())
    }

    @Test fun `a request is decided from the library rows of its game`() {
        assertEquals(Decision.Fail(RommRemoteInstall.Reasons.NOT_IN_LIBRARY), RommRemoteInstall.decide(emptyList()))
        // The game is on the device when any row of it is owned.
        assertEquals(Decision.Owned, RommRemoteInstall.decide(listOf(Row(1, "Game.zip", false), Row(2, "Game.gba", true))))
        // Otherwise the shortest file name starts, the lowest key breaking a tie.
        assertEquals(Decision.Start(Row(2, "G.zip", false)), RommRemoteInstall.decide(listOf(Row(1, "Game (USA).zip", false), Row(2, "G.zip", false))))
        assertEquals(Decision.Start(Row(1, "A.zip", false)), RommRemoteInstall.decide(listOf(Row(2, "B.zip", false), Row(1, "A.zip", false))))
    }

    @Test fun `reports carry a status and a bounded reason`() {
        val done = json(RommRemoteInstall.reportBody(Report.Kind.DONE, null)).asJsonObject
        assertEquals("done", done.get("status").asString)
        assertFalse(done.has("reason"))
        assertEquals("already_installed", json(RommRemoteInstall.reportBody(Report.Kind.ALREADY_INSTALLED, "")).asJsonObject.get("status").asString)
        val failed = json(RommRemoteInstall.reportBody(Report.Kind.FAILED, "x".repeat(900))).asJsonObject
        assertEquals("failed", failed.get("status").asString)
        assertEquals(500, failed.get("reason").asString.length)
    }

    @Test fun `a failed claim is sorted into lost pairing, switched off, or try later`() {
        assertEquals(ClaimFailure.PAIRING_LOST, RommRemoteInstall.claimFailure(401, ""))
        assertEquals(ClaimFailure.PAIRING_LOST, RommRemoteInstall.claimFailure(403, """{"detail":"Only the device itself can do this"}"""))
        assertEquals(ClaimFailure.PAIRING_LOST, RommRemoteInstall.claimFailure(404, """{"detail":"Device with ID x not found"}"""))
        assertEquals(ClaimFailure.DISABLED_ON_SERVER, RommRemoteInstall.claimFailure(404, """{"detail":"Installing on a device is disabled on this server"}"""))
        assertEquals(ClaimFailure.TEMPORARY, RommRemoteInstall.claimFailure(500, ""))
        assertEquals(ClaimFailure.TEMPORARY, RommRemoteInstall.claimFailure(409, ""))
    }

    @Test fun `the open app polls once a minute and only when set up`() {
        val now = 10_000_000L
        assertTrue(RommRemoteInstall.foregroundDue(true, true, 0, now))
        assertFalse(RommRemoteInstall.foregroundDue(true, true, now - 30_000, now))
        assertTrue(RommRemoteInstall.foregroundDue(true, true, now - 60_000, now))
        // A clock that went back does not stall the poll.
        assertTrue(RommRemoteInstall.foregroundDue(true, true, now + 5_000_000, now))
        assertFalse(RommRemoteInstall.foregroundDue(false, true, 0, now))
        assertFalse(RommRemoteInstall.foregroundDue(true, false, 0, now))
        assertEquals(15 * 60_000L, RommRemoteInstall.BACKGROUND_POLL_MS)
    }

    @Test fun `the device token and id never go into a backup, the switch may`() {
        for (key in listOf(RommRemoteInstall.DEVICE_TOKEN_KEY, RommRemoteInstall.DEVICE_ID_KEY, RommRemoteInstall.DEVICE_IDENTIFIER_KEY)) {
            assertTrue(key, CloudSettingKeys.isSecret(key))
            assertEquals("s", SettingSchema.expectedType(key))
        }
        assertFalse(CloudSettingKeys.isSecret(RommRemoteInstall.ENABLED_KEY))
        assertEquals("b", SettingSchema.expectedType(RommRemoteInstall.ENABLED_KEY))
        // A backup file that carries them anyway is ignored; the switch is read.
        assertNull(BackupJson.decodeSetting(RommRemoteInstall.DEVICE_TOKEN_KEY, json("""{"t":"s","v":"rmm_x"}""")))
        assertEquals(true, BackupJson.decodeSetting(RommRemoteInstall.ENABLED_KEY, json("""{"t":"b","v":true}""")))
    }
}
