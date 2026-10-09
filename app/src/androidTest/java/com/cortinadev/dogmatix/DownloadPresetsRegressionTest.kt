package com.cortinadev.dogmatix

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.local.DownloadPresetStore
import com.cortinadev.dogmatix.data.local.SettingsKeys
import com.cortinadev.dogmatix.util.DownloadPreset
import com.cortinadev.dogmatix.util.DownloadPresetOptions
import com.cortinadev.dogmatix.util.DownloadPresets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DownloadPresetsRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun applyIsOneTransactionAndDoesNotModifyPathsAccountsProfilesOrQueueHold() = runBlocking {
        val file = File(context.cacheDir, "presets-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val active = stringPreferencesKey("active_profile")
            val profilePref = stringPreferencesKey("profile_child_version_preferences")
            val held = booleanPreferencesKey("queue_held")
            dataStore.edit {
                it[SettingsKeys.DOWNLOAD_DIRECTORY] = "content://test/tree/games"
                it[SettingsKeys.TORBOX_API_KEY] = "test-account-value"
                it[active] = "child"; it[profilePref] = "profile-preference"; it[held] = true
            }
            val store = DownloadPresetStore(dataStore)
            val options = DownloadPresetOptions(750f, 2, 1, wifiOnly = true, chargingOnly = true,
                nightOnly = true, nightStart = 1320, nightEnd = 360, speedLimitDayOnly = true)
            store.save(DownloadPreset("custom", "Evening", options))
            val firstSeen = CompletableDeferred<Unit>()
            val observed = async { store.snapshot.onEach { firstSeen.complete(Unit) }.take(2).toList() }
            withTimeout(5000) { firstSeen.await() }
            store.apply("custom")
            val snapshots = withTimeout(5000) { observed.await() }
            assertEquals(2, snapshots.size)
            assertEquals(options, snapshots.last().current)
            val after = dataStore.data.first()
            assertEquals("content://test/tree/games", after[SettingsKeys.DOWNLOAD_DIRECTORY])
            assertEquals("test-account-value", after[SettingsKeys.TORBOX_API_KEY])
            assertEquals("child", after[active]); assertEquals("profile-preference", after[profilePref])
            assertEquals(true, after[held])
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); file.delete() }
    }

    @Test fun namedCurrentPresetAndEditedBuiltInSurviveDataStoreRestart() = runBlocking {
        val file = File(context.cacheDir, "presets-${UUID.randomUUID()}.preferences_pb")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            dataStore.edit { it[SettingsKeys.LIMIT_SPEED] = Float.POSITIVE_INFINITY; it[SettingsKeys.CONCURRENT_DOWNLOADS] = 7 }
            var store = DownloadPresetStore(dataStore)
            store.saveCurrent("  My settings  ")
            store.save(DownloadPresets.builtIns.first().copy(options = DownloadPresetOptions(limitSpeed = 500f)))
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            store = DownloadPresetStore(dataStore)
            val custom = store.snapshot.first().presets.single { !it.builtIn }
            assertEquals("My settings", custom.name)
            assertEquals(7, custom.options.concurrentDownloads)
            assertEquals(Float.POSITIVE_INFINITY, custom.options.limitSpeed)
            assertEquals(500f, store.snapshot.first().presets.first().options.limitSpeed)
            store.apply(custom.id)
            assertEquals(Float.POSITIVE_INFINITY, dataStore.data.first()[SettingsKeys.LIMIT_SPEED])
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); file.delete() }
    }

    @Test fun customDeletionDoesNotChangeCurrentSettingsAndDeletedPresetCannotBeApplied() = runBlocking {
        val file = File(context.cacheDir, "presets-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val store = DownloadPresetStore(dataStore)
            store.save(DownloadPreset("custom", "Slow", DownloadPresetOptions(limitSpeed = 500f)))
            store.apply("custom")
            val current = store.snapshot.first().current
            store.delete("custom")
            assertEquals(2, store.snapshot.first().presets.size)
            assertEquals(current, store.snapshot.first().current)
            assertTrue(runCatching { store.apply("custom") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { store.delete(DownloadPresets.DAYTIME_ID) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(current, store.snapshot.first().current)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); file.delete() }
    }
}
