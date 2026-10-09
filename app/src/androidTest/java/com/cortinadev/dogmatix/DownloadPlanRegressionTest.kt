package com.cortinadev.dogmatix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cortinadev.dogmatix.data.service.DownloadPlanPreview
import com.cortinadev.dogmatix.data.service.DownloadPlanPreviewRow
import com.cortinadev.dogmatix.ui.screens.download.DownloadPlanPreviewDialog
import com.cortinadev.dogmatix.ui.theme.DogmatixTheme
import com.cortinadev.dogmatix.util.ConditionKind
import com.cortinadev.dogmatix.util.DownloadCondition
import com.cortinadev.dogmatix.util.DownloadPlan
import com.cortinadev.dogmatix.util.DownloadPlanItem
import com.cortinadev.dogmatix.util.DownloadPlanStatus
import com.cortinadev.dogmatix.util.DownloadPlans
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class DownloadPlanRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val item = DownloadPlanItem("nintendo_gba", "portable.zip", "Portable game", DownloadCondition(ConditionKind.WIFI))

    @Test fun previewDoesNotImportUntilExplicitConfirmation() {
        var imports = 0
        val plan = DownloadPlan(listOf(item))
        compose.setContent {
            DogmatixTheme { DownloadPlanPreviewDialog(DownloadPlanPreview(plan, listOf(DownloadPlanPreviewRow(item, DownloadPlanStatus.READY))), false, { imports++ }, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.plan26_ready)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plan6_when_wifi)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, imports) }
        compose.onNodeWithText(context.getString(R.string.plan26_enqueue, 1)).performClick()
        compose.runOnIdle { assertEquals(1, imports) }
    }

    @Test fun unavailablePlanCannotBeConfirmed() {
        val plan = DownloadPlan(listOf(item))
        compose.setContent {
            DogmatixTheme { DownloadPlanPreviewDialog(DownloadPlanPreview(plan, listOf(DownloadPlanPreviewRow(item, DownloadPlanStatus.UNAVAILABLE))), false, {}, {}) }
        }
        compose.onNodeWithText(context.getString(R.string.plan26_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.plan26_enqueue, 0)).assertIsNotEnabled()
    }

    @Test fun portableJsonTravelsThroughConfiguredFileProvider() {
        val plan = DownloadPlan(listOf(item, item.copy(consoleId = "sony_psp", fileName = "other.iso", condition = DownloadCondition(ConditionKind.AT_TIME, 60, 1_800_000_000_000L))))
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "plan-regression-${UUID.randomUUID()}.json")
        try {
            file.writeText(DownloadPlans.encode(plan))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            assertEquals("content", uri.scheme)
            val received = requireNotNull(context.contentResolver.openInputStream(uri)).use(DownloadPlans::read)
            assertEquals(plan, received)
        } finally { file.delete() }
    }

    @Test fun nativeParserRejectsPrivateFieldsDuplicateFieldsAndOversizedNumbers() {
        val text = DownloadPlans.encode(DownloadPlan(listOf(item)))
        for (bad in listOf(
            text.replace("\"version\":1", "\"version\":1,\"version\":1"),
            text.replace("\"version\":1", "\"version\":9999999999999999999999999999999999"),
            text.replace("\"displayName\":\"Portable game\"", "\"displayName\":\"Portable game\",\"sourceUrl\":\"https://private\"")
        )) assertThrows(Exception::class.java) { DownloadPlans.decode(bad) }
    }
}
