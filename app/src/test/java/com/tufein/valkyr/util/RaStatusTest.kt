package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaStatusTest {

    private fun entry(id: Int, award: RaAward, total: Int = 10, earned: Int = 0, hard: Int = 0) = RaAwardEntry(id, award, total, earned, hard)

    @Test
    fun `status follows the highest award`() {
        assertEquals(RaStatus.MASTERED, RaStatuses.statusOf(entry(1, RaAward.MASTERED, earned = 10, hard = 10)))
        assertEquals(RaStatus.MASTERED, RaStatuses.statusOf(entry(1, RaAward.COMPLETED, earned = 10)))
        assertEquals(RaStatus.BEATEN, RaStatuses.statusOf(entry(1, RaAward.BEATEN, earned = 4)))
        assertEquals(RaStatus.BEATEN, RaStatuses.statusOf(entry(1, RaAward.BEATEN_SOFTCORE, earned = 4)))
        assertEquals(RaStatus.IN_PROGRESS, RaStatuses.statusOf(entry(1, RaAward.NONE, earned = 3)))
        assertEquals(RaStatus.IN_PROGRESS, RaStatuses.statusOf(entry(1, RaAward.NONE, earned = 0, hard = 2)))
        assertNull(RaStatuses.statusOf(entry(1, RaAward.NONE)))
        assertNull(RaStatuses.statusOf(null))
        assertEquals(RaStatus.BEATEN, RaStatuses.statusOf(RaAward.BEATEN, 0))
    }

    @Test
    fun `filters match only their own status`() {
        assertTrue(RaStatusFilter.ALL.matches(null) && RaStatusFilter.ALL.matches(RaStatus.BEATEN))
        assertTrue(RaStatusFilter.BEATEN.matches(RaStatus.BEATEN))
        assertFalse(RaStatusFilter.BEATEN.matches(RaStatus.MASTERED))
        assertFalse(RaStatusFilter.MASTERED.matches(null))
        assertTrue(RaStatusFilter.IN_PROGRESS.matches(RaStatus.IN_PROGRESS))
        assertFalse(RaStatusFilter.ALL.isActive)
        assertTrue(RaStatusFilter.IN_PROGRESS.isActive)
    }

    @Test
    fun `awards look a game up by id and say nothing for unknown ones`() {
        val awards = RaAwards(mapOf(5 to entry(5, RaAward.MASTERED, earned = 10, hard = 10)), 1L)
        assertEquals(RaStatus.MASTERED, awards.statusOf(5))
        assertNull(awards.statusOf(6))
        assertNull(awards.statusOf(null))
        assertNull(RaAwards().statusOf(5))
    }

    @Test
    fun `parse reads the completion list`() {
        val json = """
            {"Count":3,"Total":3,"Results":[
              {"GameID":1,"Title":"A","ConsoleID":3,"MaxPossible":20,"NumAwarded":20,"NumAwardedHardcore":20,"HighestAwardKind":"mastered"},
              {"GameID":"2","MaxPossible":"10","NumAwarded":"6","NumAwardedHardcore":"0","HighestAwardKind":"beaten-softcore"},
              {"GameID":3,"MaxPossible":8,"NumAwarded":2,"NumAwardedHardcore":2,"HighestAwardKind":null},
              {"Title":"no id"}
            ]}
        """.trimIndent()
        val page = RaApi.parseCompletionProgress(json)!!
        assertEquals(3, page.total)
        assertEquals(4, page.rows)
        assertEquals(listOf(1, 2, 3), page.entries.map { it.gameId })
        assertEquals(RaStatus.MASTERED, RaStatuses.statusOf(page.entries[0]))
        assertEquals(RaStatus.BEATEN, RaStatuses.statusOf(page.entries[1]))
        assertEquals(RaStatus.IN_PROGRESS, RaStatuses.statusOf(page.entries[2]))
    }

    @Test
    fun `parse counts alone can make a game complete, and never throws`() {
        val full = RaApi.parseCompletionProgress("""{"Total":1,"Results":[{"GameID":9,"MaxPossible":5,"NumAwarded":5,"NumAwardedHardcore":3}]}""")!!
        assertEquals(RaAward.COMPLETED, full.entries.single().award)
        assertNull(RaApi.parseCompletionProgress("<html>"))
        assertNull(RaApi.parseCompletionProgress("""{"message":"Unauthenticated."}"""))
        assertNotNull(RaApi.parseCompletionProgress("""{"Total":0,"Results":[]}"""))
        assertTrue(RaApi.parseCompletionProgress("""{"Total":0,"Results":[]}""")!!.entries.isEmpty())
    }

    @Test
    fun `merge keeps the better entry of a game listed twice`() {
        val a = RaCompletionPage(2, listOf(entry(1, RaAward.NONE, earned = 2), entry(2, RaAward.BEATEN, earned = 3)), 2)
        val b = RaCompletionPage(2, listOf(entry(1, RaAward.MASTERED, earned = 10, hard = 10), entry(2, RaAward.NONE, earned = 9)), 2)
        val merged = RaStatuses.merge(listOf(a, b))
        assertEquals(RaAward.MASTERED, merged[1]!!.award)
        assertEquals(RaAward.BEATEN, merged[2]!!.award)
    }

    @Test
    fun `paging stops at a short page, the total and the cap`() {
        fun page(rows: Int, total: Int) = RaCompletionPage(total, emptyList(), rows)
        assertEquals(500, RaStatuses.nextOffset(0, 500, page(500, 1200), 1))
        assertEquals(1000, RaStatuses.nextOffset(500, 500, page(500, 1200), 2))
        assertNull(RaStatuses.nextOffset(1000, 500, page(200, 1200), 3))
        assertNull(RaStatuses.nextOffset(0, 500, page(500, 500), 1))
        assertNull(RaStatuses.nextOffset(0, 500, page(0, 900), 1))
        assertNull(RaStatuses.nextOffset(2500, 500, page(500, 9000), RaStatuses.MAX_PAGES))
    }

    @Test
    fun `freshness follows the time to live`() {
        val awards = RaAwards(emptyMap(), 1_000L)
        assertTrue(RaStatuses.isFresh(awards, 1_000L + RaStatuses.TTL_MS - 1))
        assertFalse(RaStatuses.isFresh(awards, 1_000L + RaStatuses.TTL_MS))
        assertFalse(RaStatuses.isFresh(RaAwards(), 5_000L))
        assertFalse(RaStatuses.isFresh(awards, 500L))
    }

    @Test
    fun `cache json round trips and a broken file means never fetched`() {
        val awards = RaAwards(mapOf(1 to entry(1, RaAward.BEATEN, 12, 7, 5), 2 to entry(2, RaAward.NONE, 4, 1, 0)), 42L)
        assertEquals(awards, RaStatuses.fromJson(RaStatuses.toJson(awards)))
        assertEquals(RaAwards(), RaStatuses.fromJson("garbage"))
        assertEquals(RaAwards(), RaStatuses.fromJson(null))
    }

    @Test
    fun `counts group by status`() {
        val awards = RaAwards(
            mapOf(
                1 to entry(1, RaAward.MASTERED, earned = 10), 2 to entry(2, RaAward.COMPLETED, earned = 10),
                3 to entry(3, RaAward.BEATEN, earned = 4), 4 to entry(4, RaAward.NONE, earned = 1), 5 to entry(5, RaAward.NONE)
            ), 1L
        )
        assertEquals(mapOf(RaStatus.MASTERED to 2, RaStatus.BEATEN to 1, RaStatus.IN_PROGRESS to 1), RaStatuses.counts(awards))
    }

    @Test
    fun `the url asks for one page and keeps the key out of nothing but the query`() {
        val url = RaApi.completionProgressUrl("Some User", "k&y", 9999, -4)
        assertTrue(url.contains("API_GetUserCompletionProgress.php"))
        assertTrue(url.contains("c=500") && url.contains("o=0"))
        assertTrue(url.contains("Some+User") && url.contains("k%26y"))
    }
}
