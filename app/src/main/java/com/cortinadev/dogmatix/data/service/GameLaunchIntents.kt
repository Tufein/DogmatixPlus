package com.cortinadev.dogmatix.data.service

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.cortinadev.dogmatix.util.IntentSpec
import com.cortinadev.dogmatix.util.PlayRecipe
import com.cortinadev.dogmatix.util.SpecExtra

/** Production intent conversion, shared by catalogue recipes and the ordinary file picker. */
object GameLaunchIntents {
    private fun clipOf(uris: List<String>): ClipData =
        ClipData.newRawUri("Game", Uri.parse(uris.first())).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(Uri.parse(it))) }
        }

    fun view(uri: String, fileName: String, siblings: List<String> = emptyList()): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), PlayRecipe.mimeFor(fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { clipData = clipOf((listOf(uri) + siblings).distinct()) }

    /** Extras carry URI strings; ClipData supplies the actual Android read grants for them. */
    fun fromSpec(spec: IntentSpec): Intent = Intent().apply {
        spec.action?.let { action = it }
        if (spec.className != null) setClassName(spec.packageName, spec.className) else setPackage(spec.packageName)
        spec.categories.forEach { addCategory(it) }
        spec.dataUri?.let(Uri::parse)?.let { uri ->
            if (spec.mime != null) setDataAndType(uri, spec.mime) else data = uri
        }
        spec.extras.forEach { extra ->
            when (extra) {
                is SpecExtra.Text -> putExtra(extra.key, extra.value)
                is SpecExtra.Flag -> putExtra(extra.key, extra.value)
            }
        }
        if (spec.grantUris.isNotEmpty()) {
            clipData = clipOf(spec.grantUris.distinct())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // CLEAR_TASK needs NEW_TASK; a second game must not merely resume a running one.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (spec.clearTask) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
