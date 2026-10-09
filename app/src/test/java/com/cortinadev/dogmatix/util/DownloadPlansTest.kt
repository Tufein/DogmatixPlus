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
}
