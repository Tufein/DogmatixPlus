package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.WishlistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WishlistRommAlertsTest {
    private val wish = WishlistEntity(id = 1, title = "Chrono Trigger", consoleId = "nintendo_snes")
    private val any = WishlistEntity(id = 2, title = "Earthbound")
    private val keys = setOf("nintendo_snes|chrono trigger (usa)", "nintendo_snes|earthbound (usa)")

    @Test
    fun `a wish on the server is pending`() {
        assertEquals(listOf(wish, any), WishlistRommAlerts.pending(listOf(wish, any), keys, emptySet()) { false })
    }

    @Test
    fun `announced wishes are not announced again`() {
        val announced = setOf(WishlistRommAlerts.announceKey(wish))
        assertEquals(listOf(any), WishlistRommAlerts.pending(listOf(wish, any), keys, announced) { false })
    }

    @Test
    fun `games already on the device are skipped`() {
        assertTrue(WishlistRommAlerts.pending(listOf(wish), keys, emptySet()) { true }.isEmpty())
    }

    @Test
    fun `another console does not match`() {
        val gba = wish.copy(consoleId = "nintendo_gba")
        assertTrue(WishlistRommAlerts.pending(listOf(gba), keys, emptySet()) { false }.isEmpty())
    }

    @Test
    fun `empty server list announces nothing`() {
        assertTrue(WishlistRommAlerts.pending(listOf(wish), emptySet(), emptySet()) { false }.isEmpty())
    }

    @Test
    fun `duplicate wishes are announced once`() {
        val twin = wish.copy(id = 3)
        assertEquals(1, WishlistRommAlerts.pending(listOf(wish, twin), keys, emptySet()) { false }.size)
    }
}
