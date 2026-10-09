package com.cortinadev.dogmatix.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlansTest {
    private val first = DownloadPlanItem("nintendo_gba", "Game (Europe).zip", "Game")
    private val second = DownloadPlanItem("sony_psp", "Other.iso", "Other", DownloadCondition(ConditionKind.AT_TIME, 800, 1_800_000_000_000L))
    private val plan = DownloadPlan(listOf(second, first))

    @Test fun `round trip keeps order identity and every condition`() {
        val items = ConditionKind.entries.mapIndexed { index, kind ->
            first.copy(fileName = "$index.zip", condition = if (kind == ConditionKind.AT_TIME) second.condition else DownloadCondition(kind))
        }
        val expected = DownloadPlan(items)
        assertEquals(expected, DownloadPlans.decode(DownloadPlans.encode(expected)))
        assertEquals(plan, DownloadPlans.read(ByteArrayInputStream(DownloadPlans.encode(plan).toByteArray())))
    }

    @Test fun `portable document contains no addresses settings credentials or SAF paths`() {
        val encoded = DownloadPlans.encode(plan)
        for (privateField in listOf("downloadUrl", "sourceUrl", "torrentMagnet", "password", "token", "settings", "content://", "https://")) {
            assertFalse(encoded.contains(privateField))
        }
        assertEquals(listOf("Other.iso", "Game (Europe).zip"), DownloadPlans.decode(encoded).items.map { it.fileName })
    }

    @Test fun `trailing input duplicate fields unknown fields and coercions are rejected`() {
        val good = DownloadPlans.encode(plan)
        for (bad in listOf(
            "$good {}", good.replace("\"version\":1", "\"version\":1,\"version\":1"),
            good.replace("\"version\":1", "\"version\":\"1\""), good.replace("\"version\":1", "\"version\":1.0"),
            good.replace("\"version\":1", "\"version\":2"), good.replace("\"version\":1", "\"version\":1,\"settings\":{}"),
            good.replace("\"displayName\":\"Game\"", "\"displayName\":\"Game\",\"downloadUrl\":\"https://private\""),
            good.replace("\"kind\":\"AT_TIME\"", "\"kind\":\"UNKNOWN\""),
            good.replace("\"minuteOfDay\":800", "\"minuteOfDay\":1440"),
            good.replace("\"atMillis\":1800000000000", "\"atMillis\":0"),
            good.replace("\"consoleId\":\"sony_psp\"", "\"consoleId\":null")
        )) assertThrows("Accepted $bad", Exception::class.java) { DownloadPlans.decode(bad) }
    }

    @Test fun `invalid identities duplicate games empty and oversized plans are rejected`() {
        for (bad in listOf(
            DownloadPlan(emptyList()), DownloadPlan(listOf(first, first)),
            DownloadPlan(listOf(first.copy(fileName = "../Game.zip"))),
            DownloadPlan(listOf(first.copy(consoleId = "x/y"))),
            DownloadPlan(listOf(first.copy(displayName = "bad\nname"))),
            DownloadPlan((0..DownloadPlans.MAX_ITEMS).map { first.copy(fileName = "$it.zip") })
        )) assertThrows(IllegalArgumentException::class.java) { DownloadPlans.encode(bad) }
    }

    @Test fun `maximum size batch is allowed and bounded read stops at the byte limit`() {
        val max = DownloadPlan((0 until DownloadPlans.MAX_ITEMS).map { first.copy(fileName = "$it.zip") })
        assertEquals(max, DownloadPlans.decode(DownloadPlans.encode(max)))
        var read = 0
        val endless = object : InputStream() {
            override fun read(): Int { read++; return 'x'.code }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                repeat(length) { buffer[offset + it] = 'x'.code.toByte() }; read += length; return length
            }
        }
        assertThrows(IllegalArgumentException::class.java) { DownloadPlans.read(endless) }
        assertEquals(DownloadPlans.MAX_BYTES + 1, read)
        assertThrows(Exception::class.java) { DownloadPlans.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
    }

    @Test fun `preview skips unavailable restricted owned existing and filename conflicts`() {
        val items = (0 until 6).map { first.copy(fileName = "$it.zip") } + first.copy(consoleId = "sony_psp", fileName = "0.zip")
        val availability = items.associate { it.key to DownloadPlanAvailability(true, true) }.toMutableMap()
        availability[items[1].key] = DownloadPlanAvailability(false)
        availability[items[2].key] = DownloadPlanAvailability(true, false)
        availability[items[3].key] = DownloadPlanAvailability(true, true, false)
        availability[items[4].key] = DownloadPlanAvailability(true, true, owned = true)
        assertEquals(listOf(
            DownloadPlanStatus.READY, DownloadPlanStatus.MISSING, DownloadPlanStatus.UNAVAILABLE,
            DownloadPlanStatus.RESTRICTED, DownloadPlanStatus.OWNED, DownloadPlanStatus.ALREADY_QUEUED,
            DownloadPlanStatus.NAME_CONFLICT
        ), DownloadPlans.statuses(DownloadPlan(items), availability, setOf("5.zip")))
    }

    @Test fun `identical filename on another console is never a fallback match`() {
        val availability = mapOf(("sony_psp" to first.fileName) to DownloadPlanAvailability(true, true))
        assertEquals(listOf(DownloadPlanStatus.MISSING), DownloadPlans.statuses(DownloadPlan(listOf(first)), availability, emptySet()))
        assertTrue(DownloadPlans.decode("\uFEFF${DownloadPlans.encode(plan)}").items == plan.items)
    }

    @Test fun `listing href identities become portable without credentials or decoded percent names`() {
        for (href in listOf(
            "Game%20Name.zip?token=SENDER_SECRET#fragment",
            "./Game%20Name.zip?token=SENDER_SECRET",
            "subdir/Game%20Name.zip?password=SENDER_SECRET",
            "https://user:SENDER_SECRET@sender.invalid/library/Game%20Name.zip?token=SENDER_SECRET",
            "//sender.invalid/library/Game%20Name.zip?token=SENDER_SECRET"
        )) assertEquals("Game%20Name.zip", DownloadPlans.portableFileName(href))
        assertEquals("Game Name.zip", DownloadPlans.portableFileName("Game Name.zip"))
        assertEquals("Literal%2520Name.zip", DownloadPlans.portableFileName("Literal%2520Name.zip"))
        assertEquals(null, DownloadPlans.portableFileName("https://user:SECRET@sender.invalid/"))
        assertEquals(null, DownloadPlans.portableFileName("../"))

        val href = "https://user:SENDER_SECRET@sender.invalid/Game.zip?token=SENDER_SECRET"
        val name = requireNotNull(DownloadPlans.portableFileName(href))
        val item = first.copy(fileName = name, displayName = DownloadPlans.portableDisplayName(href, href, name))
        val encoded = DownloadPlans.encode(DownloadPlan(listOf(item)))
        assertFalse(encoded.contains("SENDER_SECRET"))
        assertFalse(encoded.contains("sender.invalid"))
        assertFalse(encoded.contains("https://"))
        assertEquals("Game.zip", DownloadPlans.decode(encoded).items.single().displayName)
    }

    @Test fun `plan schema rejects address and query credentials even inside allowed text fields`() {
        for (unsafe in listOf(
            first.copy(fileName = "Game.zip?token=SECRET"),
            first.copy(fileName = "Game.zip#SECRET"),
            first.copy(displayName = "https://user:SECRET@sender.invalid/Game.zip"),
            first.copy(displayName = "Download https://user:SECRET@sender.invalid/Game.zip"),
            first.copy(displayName = "Game.zip?token=SECRET")
        )) assertThrows(IllegalArgumentException::class.java) { DownloadPlans.encode(DownloadPlan(listOf(unsafe))) }
        val good = DownloadPlans.encode(DownloadPlan(listOf(first)))
        for (unsafe in listOf(
            good.replace("Game (Europe).zip", "Game.zip?token=SECRET"),
            good.replace("\"displayName\":\"Game\"", "\"displayName\":\"https://user:SECRET@sender.invalid/Game.zip\"")
        )) assertThrows(IllegalArgumentException::class.java) { DownloadPlans.decode(unsafe) }
    }

    @Test fun `receiver distinguishes folder collisions but recognizes matching mirror paths`() {
        assertEquals("subdir/Game.zip", DownloadPlans.localLocation("./subdir/Game.zip?token=A", "https://a.invalid/library"))
        assertEquals("subdir/Game.zip", DownloadPlans.localLocation("https://b.invalid/other/subdir/Game.zip?token=B", "https://b.invalid/other"))
        assertFalse(DownloadPlans.localLocation("A/Game.zip", "") == DownloadPlans.localLocation("B/Game.zip", ""))
        val availability = mapOf(first.key to DownloadPlanAvailability(true, true, ambiguous = true))
        assertEquals(listOf(DownloadPlanStatus.AMBIGUOUS), DownloadPlans.statuses(DownloadPlan(listOf(first)), availability, emptySet()))
    }

    @Test fun `queue collision checks use the original receiving names`() {
        val other = first.copy(consoleId = "sony_psp")
        val localName = "./${first.fileName}?token=RECEIVER"
        val availability = listOf(first, other).associate { it.key to DownloadPlanAvailability(true, true, queueFileName = localName) }
        assertEquals(listOf(DownloadPlanStatus.ALREADY_QUEUED),
            DownloadPlans.statuses(DownloadPlan(listOf(first)), availability, setOf(localName)))
        assertEquals(listOf(DownloadPlanStatus.READY, DownloadPlanStatus.NAME_CONFLICT),
            DownloadPlans.statuses(DownloadPlan(listOf(first, other)), availability, emptySet()))
        assertEquals(listOf(DownloadPlanStatus.ALREADY_QUEUED), DownloadPlans.statuses(DownloadPlan(listOf(first)),
            mapOf(first.key to DownloadPlanAvailability(true, true, alreadyQueued = true)), emptySet()))
    }
}
