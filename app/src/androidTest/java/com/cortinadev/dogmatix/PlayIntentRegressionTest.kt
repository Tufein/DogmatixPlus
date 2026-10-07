package com.cortinadev.dogmatix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.GameLaunchIntents
import com.cortinadev.dogmatix.util.IntentSpec
import com.cortinadev.dogmatix.util.SpecExtra
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlayIntentRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val probePackage get() = InstrumentationRegistry.getInstrumentation().context.packageName
    private val probeClass = "com.cortinadev.dogmatix.PlayGrantProbeActivity"

    private fun receive(build: (String, String) -> Intent) {
        val folder = File(context.cacheDir, "exports/play-probe-${UUID.randomUUID()}").apply { mkdirs() }
        fun uri(name: String, text: String) = File(folder, name).apply { writeText(text) }.let {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", it).toString()
        }
        val game = uri("Game.cue", "FILE Track.bin BINARY")
        val track = uri("Track.bin", "track bytes")
        val save = uri("Game.srm", "private save progress")
        val action = "${context.packageName}.PLAY_PROBE_${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        var result: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { result = intent; latch.countDown() }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(action)) }
        try {
            val intent = build(game, track).putExtra("callbackAction", action).putExtra("callbackPackage", context.packageName)
                .putExtra("ungrantedUri", save)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
            context.startActivity(intent)
            assertTrue("External receiver did not report its read results", latch.await(15, TimeUnit.SECONDS))
            val received = requireNotNull(result)
            assertNotEquals("The receiver must run outside the target app's UID", Process.myUid(), received.getIntExtra("uid", -1))
            assertEquals(2, received.getIntExtra("count", -1))
            assertEquals(game, received.getStringExtra("uri_0"))
            assertEquals(track, received.getStringExtra("uri_1"))
            assertEquals("FILE Track.bin BINARY", received.getStringExtra("text_0"))
            assertEquals("track bytes", received.getStringExtra("text_1"))
            assertTrue("A sibling save must remain inaccessible: $received", received.getBooleanExtra("ungrantedDenied", false))
            if (intent.data == null) assertEquals(game, received.getStringExtra("bootPath"))
            else {
                assertEquals(game, received.getStringExtra("data"))
                assertEquals(intent.type, received.getStringExtra("mime"))
            }
        } finally {
            context.unregisterReceiver(receiver)
            listOf(game, track, save).forEach { context.revokeUriPermission(android.net.Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            folder.deleteRecursively()
        }
    }

    @Test fun catalogueExtrasGrantTheGameAndTrackButNeverAnUnselectedSave() = receive { game, track ->
        GameLaunchIntents.fromSpec(IntentSpec(null, probePackage, probeClass, emptyList(), null, null,
            listOf(SpecExtra.Text("bootPath", game)), false, listOf(game, track)))
    }

    @Test fun genericArchiveMimeIsDiscoverableAndCarriesTheSameReadGrants() = receive { game, track ->
        val intent = GameLaunchIntents.view(game, "Game.zip", listOf(track))
        @Suppress("DEPRECATION")
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("Archive MIME query must see the external handler", handlers.any { it.activityInfo.packageName == probePackage && it.activityInfo.name == probeClass })
        intent.setClassName(probePackage, probeClass)
    }
}
