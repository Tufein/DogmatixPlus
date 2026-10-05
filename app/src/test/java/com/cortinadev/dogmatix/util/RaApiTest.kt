package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaApiTest {

    /** RA 2024 shape: numbers as numbers, per-game progress in "Awarded", nested RecentAchievements. */
    private val summaryNew = """
        {
          "User": "MaxMilyin",
          "ULID": "00003EMFWR7XB8SDPEHB3K56ZQ",
          "MemberSince": "2016-01-02 00:43:04",
          "LastActivity": {"ID": 0, "timestamp": null, "lastupdate": null, "activitytype": null, "User": "MaxMilyin", "data": null, "data2": null},
          "RichPresenceMsg": "Playing Sonic the Hedgehog, Green Hill Act 2",
          "LastGameID": 1,
          "ContribCount": 0,
          "ContribYield": 0,
          "TotalPoints": 3534,
          "TotalSoftcorePoints": 120,
          "TotalTruePoints": 10483,
          "Permissions": 1,
          "Untracked": 0,
          "ID": 16446,
          "UserWallActive": 1,
          "Motto": "Gotta go fast",
          "Rank": 9165,
          "RecentlyPlayedCount": 2,
          "RecentlyPlayed": [
            {"GameID": 1, "ConsoleID": 1, "ConsoleName": "Mega Drive", "Title": "Sonic the Hedgehog",
             "ImageIcon": "/Images/067895.png", "ImageTitle": "/Images/054993.png", "ImageIngame": "/Images/000010.png",
             "ImageBoxArt": "/Images/051872.png", "LastPlayed": "2023-03-17 01:13:43", "AchievementsTotal": 23},
            {"GameID": 515, "ConsoleID": 5, "ConsoleName": "Game Boy Advance", "Title": "Pokemon Emerald Version",
             "ImageIcon": "/Images/074227.png", "LastPlayed": "2023-03-18 09:00:00", "AchievementsTotal": 76}
          ],
          "Awarded": {
            "1": {"NumPossibleAchievements": 23, "PossibleScore": 400, "NumAchieved": 12, "ScoreAchieved": 105,
                  "NumAchievedHardcore": 10, "ScoreAchievedHardcore": 95},
            "515": {"NumPossibleAchievements": 76, "PossibleScore": 1000, "NumAchieved": 76, "ScoreAchieved": 1000,
                    "NumAchievedHardcore": 76, "ScoreAchievedHardcore": 1000}
          },
          "RecentAchievements": {
            "1": {
              "9": {"ID": 9, "GameID": 1, "GameTitle": "Sonic the Hedgehog", "Title": "That Was Easy",
                    "Description": "Complete the first act in Green Hill Zone", "Points": 3, "BadgeName": "250336",
                    "IsAwarded": "1", "DateAwarded": "2023-03-17 01:10:00", "HardcoreAchieved": 1},
              "10": {"ID": 10, "GameID": 1, "GameTitle": "Sonic the Hedgehog", "Title": "Speed Run 1",
                     "Description": "Complete Green Hill Zone Act 1 in under 30 seconds", "Points": 10, "BadgeName": 250337,
                     "IsAwarded": "1", "DateAwarded": "2023-03-17 01:12:00", "HardcoreAchieved": 0}
            }
          },
          "LastGame": {"ID": 1, "Title": "Sonic the Hedgehog"},
          "UserPic": "/UserPic/MaxMilyin.png",
          "TotalRanked": 46415,
          "Status": "Offline"
        }
    """.trimIndent()

    /** Older shape: everything as strings, progress inline in RecentlyPlayed, empty maps as `[]`. */
    private val summaryOld = """
        {
          "RecentlyPlayedCount": "1",
          "RecentlyPlayed": [
            {"GameID": "228", "ConsoleID": "3", "ConsoleName": "SNES", "Title": "Super Mario World",
             "ImageIcon": "/Images/066593.png", "LastPlayed": "2019-07-02 20:21:11",
             "NumPossibleAchievements": "89", "PossibleScore": "1000", "NumAchieved": "30", "ScoreAchieved": "300",
             "NumAchievedHardcore": "0", "ScoreAchievedHardcore": "0"}
          ],
          "MemberSince": "2013-02-01 12:00:00",
          "LastGameID": "228",
          "RichPresenceMsg": "",
          "TotalPoints": "1,250",
          "TotalTruePoints": "2604",
          "Rank": null,
          "Motto": "",
          "UserPic": "",
          "RecentAchievements": [],
          "Awarded": []
        }
    """.trimIndent()

    private val progress = """
        {
          "ID": 1,
          "Title": "Sonic the Hedgehog",
          "ConsoleID": 1,
          "ForumTopicID": 112,
          "Flags": 0,
          "ImageIcon": "/Images/067895.png",
          "ImageTitle": "/Images/054993.png",
          "ImageIngame": "/Images/000010.png",
          "ImageBoxArt": "/Images/051872.png",
          "Publisher": "Sega",
          "Developer": "Sonic Team",
          "Genre": "Platforming",
          "Released": "1991-06-23",
          "IsFinal": false,
          "RichPresencePatch": "cce60593880d25c97797446ed33eaffb",
          "players_total": 27080,
          "achievements_published": 4,
          "points_total": 33,
          "NumDistinctPlayers": "27080",
          "NumDistinctPlayersCasual": 27080,
          "NumDistinctPlayersHardcore": 10450,
          "ConsoleName": "Mega Drive",
          "NumAchievements": 4,
          "Achievements": {
            "9": {"ID": 9, "NumAwarded": 24273, "NumAwardedHardcore": 10450, "Title": "That Was Easy",
                  "Description": "Complete the first act in Green Hill Zone", "Points": 3, "TrueRatio": 3,
                  "Author": "Scott", "DateModified": "2023-08-08 00:36:59", "DateCreated": "2012-11-02 00:03:12",
                  "BadgeName": "250336", "DisplayOrder": 1, "MemAddr": "0xfe10=h0001_0xh00e0=h0002", "type": "progression",
                  "DateEarned": "2022-08-23 22:56:38", "DateEarnedHardcore": "2022-08-23 22:56:38"},
            "11": {"ID": "11", "NumAwarded": "13000", "Title": "Ring Collector", "Description": "Collect 100 rings",
                   "Points": "10", "TrueRatio": "14", "BadgeName": "250338", "DisplayOrder": "3", "type": null,
                   "DateEarned": "2022-08-24 10:00:00"},
            "12": {"ID": 12, "NumAwarded": 900, "NumAwardedHardcore": 271, "Title": "Chaos Emerald",
                   "Description": "Get a Chaos Emerald in the first special stage", "Points": 10, "TrueRatio": 60,
                   "BadgeName": "250339", "DisplayOrder": 2, "type": "missable", "DateEarned": "", "DateEarnedHardcore": null},
            "10": {"ID": 10, "NumAwarded": 5000, "Title": "Speed Run 1",
                   "Description": "Complete Green Hill Zone Act 1 in under 30 seconds", "Points": 10, "TrueRatio": 21,
                   "BadgeName": "250337", "DisplayOrder": 4}
          },
          "NumAwardedToUser": 2,
          "NumAwardedToUserHardcore": 1,
          "UserCompletion": "50.00%",
          "UserCompletionHardcore": "25.00%",
          "HighestAwardKind": null,
          "HighestAwardDate": null
        }
    """.trimIndent()

    @Test fun `user summary in the current shape`() {
        val s = RaApi.parseUserSummary(summaryNew, "maxmilyin")
        assertNotNull(s); s!!
        assertEquals("MaxMilyin", s.user)
        assertEquals("https://media.retroachievements.org/UserPic/MaxMilyin.png", s.avatarUrl)
        assertEquals(3534, s.points)
        assertEquals(120, s.softcorePoints)
        assertEquals(10483, s.truePoints)
        assertEquals(9165, s.rank)
        assertEquals(46415, s.totalRanked)
        assertEquals(20, s.topPercent)
        assertEquals("Gotta go fast", s.motto)
        assertEquals(2, s.recentlyPlayed.size)
        // Newest first.
        val emerald = s.recentlyPlayed[0]
        assertEquals(515, emerald.gameId)
        assertTrue(emerald.mastered)
        val sonic = s.recentlyPlayed[1]
        assertEquals("Sonic the Hedgehog", sonic.title)
        assertEquals("Mega Drive", sonic.consoleName)
        assertEquals(1, sonic.consoleId)
        assertEquals("https://media.retroachievements.org/Images/067895.png", sonic.iconUrl)
        assertEquals(23, sonic.possible)
        assertEquals(12, sonic.achieved)
        assertEquals(10, sonic.achievedHardcore)
        assertEquals(12f / 23f, sonic.fraction, 0.0001f)
        assertFalse(sonic.mastered)
        assertEquals(1679015623000L, sonic.lastPlayed)
        // Recent unlocks: flattened, newest first, numeric badge name padded.
        assertEquals(listOf(10, 9), s.recentUnlocks.map { it.achievementId })
        assertEquals("https://media.retroachievements.org/Badge/250337.png", s.recentUnlocks[0].badgeUrl)
        assertFalse(s.recentUnlocks[0].hardcore)
        assertTrue(s.recentUnlocks[1].hardcore)
    }

    @Test fun `user summary in the old all-strings shape`() {
        val s = RaApi.parseUserSummary(summaryOld, "OldTimer")
        assertNotNull(s); s!!
        assertEquals("OldTimer", s.user)
        assertEquals("https://media.retroachievements.org/UserPic/OldTimer.png", s.avatarUrl)
        assertEquals(1250, s.points)
        assertEquals(2604, s.truePoints)
        assertNull(s.rank)
        assertNull(s.topPercent)
        assertTrue(s.recentUnlocks.isEmpty())
        val smw = s.recentlyPlayed.single()
        assertEquals(228, smw.gameId)
        assertEquals(89, smw.possible)
        assertEquals(30, smw.achieved)
        assertEquals(0, smw.achievedHardcore)
        assertEquals(300, smw.scoreAchieved)
    }

    @Test fun `summary parser never throws and rejects what is no summary`() {
        assertNull(RaApi.parseUserSummary(""))
        assertNull(RaApi.parseUserSummary("<html>502 Bad Gateway</html>"))
        assertNull(RaApi.parseUserSummary("[]"))
        assertNull(RaApi.parseUserSummary("""{"message":"Unauthenticated."}""", "me"))
        assertNull(RaApi.parseUserSummary("""{"ID":null,"User":null,"TotalPoints":null}""", "ghost"))
        // Odd field types are skipped, not fatal.
        val s = RaApi.parseUserSummary("""{"User":"x","TotalPoints":{"a":1},"RecentlyPlayed":[1,"two",{"GameID":"abc"},{"GameID":7,"Title":"Ok"}],"Rank":"0"}""")
        assertNotNull(s); s!!
        assertEquals(0, s.points)
        assertNull(s.rank)
        assertEquals(listOf(7), s.recentlyPlayed.map { it.gameId })
    }

    @Test fun `game progress with achievements keyed by id`() {
        val p = RaApi.parseGameProgress(progress)
        assertNotNull(p); p!!
        assertEquals(1, p.gameId)
        assertEquals("Sonic the Hedgehog", p.title)
        assertEquals("Mega Drive", p.consoleName)
        assertEquals("https://media.retroachievements.org/Images/067895.png", p.iconUrl)
        assertEquals(4, p.total)
        assertEquals(2, p.earned)
        assertEquals(1, p.earnedHardcore)
        assertEquals(33, p.points)
        assertEquals(13, p.earnedPoints)
        assertEquals(0.5f, p.completion, 0.0001f)
        assertEquals(RaAward.NONE, p.award)
        assertEquals(27080, p.players)
        // RA's display order, ids as strings or numbers alike.
        assertEquals(listOf(9, 12, 11, 10), p.achievements.map { it.id })
        val easy = p.achievements[0]
        assertTrue(easy.earned)
        assertTrue(easy.earnedHardcore)
        assertEquals(1661295398000L, easy.earnedAt)
        assertEquals("https://media.retroachievements.org/Badge/250336.png", easy.badgeUrl)
        assertEquals("https://media.retroachievements.org/Badge/250336_lock.png", easy.lockedBadgeUrl)
        assertEquals(10450f / 27080f, easy.rarity!!, 0.0001f)
        val emerald = p.achievements[1]
        assertFalse("empty DateEarned is not earned", emerald.earned)
        assertNull(emerald.earnedAt)
        assertTrue(emerald.missable)
        val rings = p.achievements[2]
        assertTrue("softcore only", rings.earned)
        assertFalse(rings.earnedHardcore)
        assertEquals(10, rings.points)
        assertEquals(14, rings.truePoints)
        assertNull(rings.type)
        val speed = p.achievements[3]
        assertFalse("missing DateEarned is not earned", speed.earned)
    }

    @Test fun `game progress edge cases`() {
        // No achievements: RA sends an empty array instead of an object.
        val empty = RaApi.parseGameProgress("""{"ID":"30000","Title":"Homebrew","ConsoleName":"NES","NumAchievements":"0","Achievements":[],"UserCompletion":"0.00%"}""")
        assertNotNull(empty); empty!!
        assertFalse(empty.hasAchievements)
        assertEquals(0f, empty.completion, 0f)
        // Unknown game, error bodies, garbage.
        assertNull(RaApi.parseGameProgress("""{"ID":0,"Title":null,"Achievements":[]}"""))
        assertNull(RaApi.parseGameProgress("""{"message":"Unauthenticated."}"""))
        assertNull(RaApi.parseGameProgress("[]"))
        assertNull(RaApi.parseGameProgress("not json"))
        // No list but the counts are there: fall back to them and to UserCompletion.
        val counts = RaApi.parseGameProgress("""{"ID":5,"Title":"X","NumAchievements":"40","NumAwardedToUser":"10","NumAwardedToUserHardcore":"40x","UserCompletion":"25.00%"}""")
        assertNotNull(counts); counts!!
        assertEquals(40, counts.total)
        assertEquals(10, counts.earned)
        assertEquals(0, counts.earnedHardcore)
        assertEquals(0.25f, counts.completion, 0.0001f)
        // An achievement with a broken shape is skipped, the rest stays.
        val broken = RaApi.parseGameProgress("""{"ID":6,"Title":"Y","Achievements":{"1":"oops","2":{"ID":2,"Title":"Fine","Points":5,"BadgeName":7}}}""")
        assertEquals(listOf(2), broken!!.achievements.map { it.id })
        assertEquals("https://media.retroachievements.org/Badge/00007.png", broken.achievements[0].badgeUrl)
    }

    @Test fun `awards from the api and from the counts`() {
        val all = """{"ID":7,"Title":"Z","Achievements":{
            "1":{"ID":1,"Points":5,"DateEarned":"2024-01-01 10:00:00","DateEarnedHardcore":"2024-01-01 10:00:00"},
            "2":{"ID":2,"Points":5,"DateEarned":"2024-01-02 10:00:00","DateEarnedHardcore":"2024-01-02 10:00:00"}},
            "HighestAwardKind":"beaten-hardcore"}"""
        assertEquals(RaAward.MASTERED, RaApi.parseGameProgress(all)!!.award)
        val beaten = """{"ID":8,"Title":"Z","Achievements":{"1":{"ID":1,"DateEarned":"2024-01-01 10:00:00"},"2":{"ID":2}},"HighestAwardKind":"beaten-softcore"}"""
        assertEquals(RaAward.BEATEN_SOFTCORE, RaApi.parseGameProgress(beaten)!!.award)
        val soft = """{"ID":9,"Title":"Z","Achievements":{"1":{"ID":1,"DateEarned":"2024-01-01 10:00:00"}}}"""
        assertEquals(RaAward.COMPLETED, RaApi.parseGameProgress(soft)!!.award)
    }

    @Test fun `rows put what is left first, then the newest unlocks`() {
        val p = RaApi.parseGameProgress(progress)!!
        assertEquals(listOf(12, 10, 11, 9), RaApi.rowsOrder(p.achievements).map { it.id })
    }

    @Test fun `dates in every shape RA uses`() {
        assertEquals(1679015623000L, RaApi.parseDate("2023-03-17 01:13:43"))
        assertEquals(1679015623000L, RaApi.parseDate("2023-03-17T01:13:43.000000Z"))
        assertEquals(1679015623000L, RaApi.parseDate("2023-03-17T03:13:43+02:00"))
        assertEquals(1678924800000L, RaApi.parseDate("2023-03-16"))
        assertEquals(1679015623000L, RaApi.parseDate("1679015623"))
        assertNull(RaApi.parseDate(""))
        assertNull(RaApi.parseDate(null))
        assertNull(RaApi.parseDate("0000-00-00 00:00:00"))
        assertNull(RaApi.parseDate("yesterday"))
    }

    @Test fun `urls media and errors`() {
        val url = RaApi.userSummaryUrl("Me Too", "k&y", recentGames = 5, recentAchievements = 5)
        assertTrue(url.startsWith("https://retroachievements.org/API/API_GetUserSummary.php?"))
        assertTrue(url.contains("u=Me+Too") && url.contains("y=k%26y") && url.contains("&g=5&a=5"))
        assertTrue(RaApi.gameProgressUrl(515, "me", "key").contains("API_GetGameInfoAndUserProgress.php?") )
        assertTrue(RaApi.gameProgressUrl(515, "me", "key").contains("&g=515&u=me"))
        assertNull(RaApi.mediaUrl(""))
        assertNull(RaApi.mediaUrl(null))
        assertEquals("https://media.retroachievements.org/Images/1.png", RaApi.mediaUrl("Images/1.png"))
        assertEquals("https://cdn.example/x.png", RaApi.mediaUrl("https://cdn.example/x.png"))
        assertEquals("https://media.retroachievements.org/Badge/00000_lock.png", RaApi.badgeUrl("../", locked = true))

        assertEquals(RaErrorKind.BAD_KEY, RaApi.errorKindFor(401, """{"message":"Unauthenticated."}"""))
        assertEquals(RaErrorKind.BAD_KEY, RaApi.errorKindFor(200, """{"Error":"Invalid API Key"}"""))
        assertEquals(RaErrorKind.RATE_LIMITED, RaApi.errorKindFor(429, null))
        assertEquals(RaErrorKind.SERVER, RaApi.errorKindFor(503, "<html>"))
        assertEquals(RaErrorKind.NOT_FOUND, RaApi.errorKindFor(422, """{"message":"The selected u is invalid."}"""))
        assertEquals(RaErrorKind.BAD_RESPONSE, RaApi.errorKindFor(200, "garbage"))
        assertEquals(RaErrorKind.BAD_KEY, RaApi.unreadableKind("""{"message":"Unauthenticated."}"""))
        assertEquals(RaErrorKind.NOT_FOUND, RaApi.unreadableKind("""{"ID":null,"Title":null}"""))
        assertEquals(RaErrorKind.NOT_FOUND, RaApi.unreadableKind("[]"))
        assertEquals(RaErrorKind.BAD_RESPONSE, RaApi.unreadableKind("<html>Bad gateway</html>"))
        assertEquals(RaErrorKind.BAD_RESPONSE, RaApi.unreadableKind("{\"User\":\"cut"))
        assertEquals(1, RaApi.topPercent(1, 46415))
        assertEquals(100, RaApi.topPercent(46415, 46415))
        assertNull(RaApi.topPercent(0, 10))
    }

    @Test fun `the diagnostics redactor removes RA urls and the key`() {
        val key = "AbCdEf0123456789AbCdEf0123456789"
        val line = "GET " + RaApi.gameProgressUrl(1, "me", key) + " failed; key was $key"
        val out = DiagnosticsRedactor.redact(line, listOf(key))
        assertFalse(out.contains(key))
        assertFalse(out.contains("y="))
    }
}
