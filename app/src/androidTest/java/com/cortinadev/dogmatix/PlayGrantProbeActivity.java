package com.cortinadev.dogmatix;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Runs in the test APK's separate UID, so a successful read proves an actual external URI grant. */
public class PlayGrantProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent(getIntent().getStringExtra("callbackAction"))
            .setPackage(getIntent().getStringExtra("callbackPackage"));
        result.putExtra("uid", Process.myUid());
        result.putExtra("bootPath", getIntent().getStringExtra("bootPath"));
        result.putExtra("mime", getIntent().getType());
        result.putExtra("data", getIntent().getDataString());
        ClipData clip = getIntent().getClipData();
        int count = clip == null ? 0 : clip.getItemCount();
        result.putExtra("count", count);
        for (int i = 0; i < count; i++) {
            Uri uri = clip.getItemAt(i).getUri();
            result.putExtra("uri_" + i, uri.toString());
            try { result.putExtra("text_" + i, read(uri)); }
            catch (Exception e) { result.putExtra("error_" + i, e.getClass().getSimpleName()); }
        }
        try {
            read(Uri.parse(getIntent().getStringExtra("ungrantedUri")));
            result.putExtra("ungrantedDenied", false);
        } catch (SecurityException expected) {
            result.putExtra("ungrantedDenied", true);
        } catch (Exception wrongFailure) {
            result.putExtra("ungrantedError", wrongFailure.getClass().getSimpleName());
        }
        sendBroadcast(result);
        finish();
    }

    private String read(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
}
