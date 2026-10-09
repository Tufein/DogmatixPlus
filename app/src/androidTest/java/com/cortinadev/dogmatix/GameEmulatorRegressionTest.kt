package com.cortinadev.dogmatix

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.GameEmulatorPreferences
import com.cortinadev.dogmatix.util.GameLaunchKeys
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Android preferences and the emulator portion used by BackupService. */
class GameEmulatorRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val emulator = GameLaunchKeys.catalogue("ppsspp", packageName = "org.ppsspp.ppssppgold")

    @Test fun canonicalChoicesPersistAndClearWithoutChangingAnotherGameOrConsoleDefault() {
        val file = "emulators-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("psp", GameLaunchKeys.AUTOMATIC).commit()
            val store = GameEmulatorPreferences(prefs) { "" }
            store.setGame("psp", "Game (Europe).iso", emulator)
            store.setGame("psp", "Other.iso", "other.package/other.package.Main")
            val restarted = GameEmulatorPreferences(context.getSharedPreferences(file, Context.MODE_PRIVATE)) { "" }
            assertEquals(emulator, restarted.game("PSP", "Game%20%28USA%29%20%28Rev%202%29.iso"))
            assertNull(restarted.game("gba", "Game (Europe).iso"))
            restarted.setGame("psp", "Game (USA).iso", null)
            assertNull(store.game("psp", "Game (Europe).iso"))
            assertEquals(GameLaunchKeys.AUTOMATIC, store.console("psp"))
            assertEquals("other.package/other.package.Main", store.game("psp", "Other.iso"))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun switchingProfilesDoesNotLeakAnOverrideAndBackupsRestoreEveryProfile() {
        val file = "emulators-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        var active = "child"
        val store = GameEmulatorPreferences(prefs) { active }
        try {
            store.setGame("psp", "Game.iso", emulator)
            active = ""
            assertNull(store.game("psp", "Game.iso"))
            store.setGame("psp", "Game.iso", "other.package/other.package.Main")
            prefs.edit().putString("psp", GameLaunchKeys.AUTOMATIC).commit()
            val backup = JsonParser.parseString(GameEmulatorPreferences.export(prefs).toString()).asJsonObject
            prefs.edit().clear().commit()
            assertNull(store.game("psp", "Game.iso"))
            GameEmulatorPreferences.restore(prefs, backup)
            assertEquals("other.package/other.package.Main", store.game("psp", "Game.iso"))
            assertEquals(GameLaunchKeys.AUTOMATIC, store.console("psp"))
            active = "child"
            assertEquals(emulator, store.game("psp", "Game.iso"))
            assertNull(store.console("psp"))
        } finally { prefs.edit().clear().commit() }
    }
}
