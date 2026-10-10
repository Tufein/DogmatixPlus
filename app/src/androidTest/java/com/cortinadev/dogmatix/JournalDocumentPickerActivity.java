package com.cortinadev.dogmatix;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;

/** The provider owner's UID offers a persistable document result, just like DocumentsUI. */
public class JournalDocumentPickerActivity extends Activity {
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        Intent result = new Intent().setData(getIntent().getData())
            .setClipData(ClipData.newRawUri("document", getIntent().getData()))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }
}
