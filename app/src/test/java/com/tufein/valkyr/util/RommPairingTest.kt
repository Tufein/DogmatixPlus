package com.tufein.valkyr.util

import com.google.gson.JsonParser
import com.tufein.valkyr.util.RommPairing.Exchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RommPairingTest {

    @Test fun `a typed code is read the way RomM reads it`() {
        assertEquals("ABCD2345", RommPairing.normalize("abcd-2345"))
        assertEquals("ABCD2345", RommPairing.normalize(" ab cd - 23 45 "))
        // Never longer than a code.
        assertEquals("ABCD2345", RommPairing.normalize("abcd-2345-xyz"))
        assertEquals("ABCD-2345", RommPairing.display("abcd2345"))
        assertEquals("ABC", RommPairing.display("abc"))
    }

    @Test fun `only eight characters of the server alphabet make a complete code`() {
        assertTrue(RommPairing.isComplete("ABCD-2345"))
        assertFalse(RommPairing.isComplete("ABCD-234"))
        // 0, 1, I, L and O are not in RomM's alphabet.
        assertFalse(RommPairing.isComplete("ABCD-2340"))
        assertFalse(RommPairing.isComplete("OOOOIIII"))
        assertEquals(setOf('0', 'O'), RommPairing.invalidChars("ab0o2345").toSet())
        assertEquals("", RommPairing.invalidChars("ABCD2345"))
    }

    @Test fun `the exchange body carries the normalised code`() {
        val json = JsonParser.parseString(RommPairing.exchangeBody("abcd-2345")).asJsonObject
        assertEquals("ABCD2345", json.get("code").asString)
        assertEquals(1, json.size())
    }

    @Test fun `a good answer gives the new token`() {
        val json = JsonParser.parseString("""{"id":3,"name":"Handheld","raw_token":"rmm_abc123","scopes":["roms.read"]}""")
        assertEquals(Exchange.Paired("rmm_abc123", "Handheld"), RommPairing.parseExchange(201, json))
        assertEquals(Exchange.Paired("rmm_abc123", "Handheld"), RommPairing.parseExchange(200, json))
    }

    @Test fun `an answer without a client token is no pairing`() {
        assertEquals(Exchange.Failed(0), RommPairing.parseExchange(200, JsonParser.parseString("""{"raw_token":"abc"}""")))
        assertEquals(Exchange.Failed(0), RommPairing.parseExchange(200, JsonParser.parseString("""{"raw_token":"rmm_"}""")))
        assertEquals(Exchange.Failed(0), RommPairing.parseExchange(200, null))
        assertEquals(Exchange.Failed(0), RommPairing.parseExchange(200, JsonParser.parseString("[1]")))
    }

    @Test fun `errors are told apart`() {
        assertEquals(Exchange.UnknownCode, RommPairing.parseExchange(404, null))
        assertEquals(Exchange.UnknownCode, RommPairing.parseExchange(405, null))
        assertEquals(Exchange.TooManyTries, RommPairing.parseExchange(429, null))
        assertEquals(Exchange.OwnerDisabled, RommPairing.parseExchange(403, null))
        assertEquals(Exchange.Failed(500), RommPairing.parseExchange(500, null))
        assertEquals(Exchange.Failed(301), RommPairing.parseExchange(301, null))
    }
}
