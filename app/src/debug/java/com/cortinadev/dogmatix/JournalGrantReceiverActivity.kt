package com.cortinadev.dogmatix

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Debug-only receiver for a document result issued by the independent test application. */
class JournalGrantReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickedUri = null
        startActivityForResult(Intent().setClassName(packageName + ".test", "com.cortinadev.dogmatix.JournalDocumentPickerActivity")
            .setData(intent.data), 1)
    }
    @Deprecated("Test fixture for the platform document-result grant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) pickedUri = data?.data
    }
    companion object { @Volatile var pickedUri: Uri? = null }
}
