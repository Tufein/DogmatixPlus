package com.cortinadev.dogmatix;

import android.net.Uri;
import android.provider.DocumentsContract;

/** Independent provider authority supplies real MIME and persistable-document checks. */
public class JournalAttachmentTestProvider extends TestStorageProvider {
    @Override public String getType(Uri uri) {
        String name = DocumentsContract.getDocumentId(uri).toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".pdf")) return "application/pdf";
        return super.getType(uri);
    }
}
