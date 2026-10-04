package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.WishlistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WishlistShareTest {
    @Test fun `a wishlist survives the file and starts as wanted again`() {
        val rows = listOf(
            WishlistEntity(title = "Metroid Fusion", consoleId = "gba", notifiedAt = 123L),
            WishlistEntity(title = "Chrono Trigger")
        )
        val back = WishlistShare.parse(WishlistShare.export(rows))
        assertNotNull(back)
        assertEquals(listOf("Metroid Fusion" to "gba", "Chrono Trigger" to null), back!!.map { it.title to it.consoleId })
        assertEquals(listOf<Long?>(null, null), back.map { it.notifiedAt })
    }

    @Test fun `other json and plain text are refused`() {
        assertNull(WishlistShare.parse("""{"format":"dogmatix-backup","items":[]}"""))
        assertNull(WishlistShare.parse("not json at all"))
        assertNull(WishlistShare.parse("[1,2,3]"))
    }

    @Test fun `a damaged row is skipped`() {
        val text = """{"format":"dogmatix-wishlist","version":1,"items":[{"title":"x"},{"title":"Sonic CD","consoleId":"segacd"},42]}"""
        assertEquals(listOf("Sonic CD"), WishlistShare.parse(text)!!.map { it.title })
    }

    @Test fun `a file with a byte order mark is read`() {
        assertNotNull(WishlistShare.parse("\uFEFF" + WishlistShare.export(emptyList())))
    }
}
