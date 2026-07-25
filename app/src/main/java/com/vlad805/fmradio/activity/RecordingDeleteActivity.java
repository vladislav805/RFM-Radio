package com.vlad805.fmradio.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

import com.vlad805.fmradio.R;
import com.vlad805.fmradio.service.recording.RecordingStorage;

import java.io.IOException;

public final class RecordingDeleteActivity extends AppCompatActivity {
    public static final int NOTIFICATION_ID = 1030;

    private static final String EXTRA_URI = "recording_uri";
    private static final String EXTRA_FILE_PATH = "recording_file_path";
    private static final String EXTRA_DISPLAY_NAME = "display_name";
    private static final String EXTRA_NOTIFICATION_TAG = "notification_tag";

    public static Intent createIntent(
            final Context context,
            final String uri,
            final String filePath,
            final String displayName,
            final String notificationTag
    ) {
        final String identity = uri != null ? uri : filePath;
        return new Intent(context, RecordingDeleteActivity.class)
                .setData(Uri.fromParts("rfm-recording", Integer.toHexString(identity.hashCode()), null))
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_FILE_PATH, filePath)
                .putExtra(EXTRA_DISPLAY_NAME, displayName)
                .putExtra(EXTRA_NOTIFICATION_TAG, notificationTag);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        final String uri = intent.getStringExtra(EXTRA_URI);
        final String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
        final String displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME);
        final String notificationTag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG);

        if (
                (uri == null && filePath == null) ||
                displayName == null ||
                notificationTag == null
        ) {
            finish();
            return;
        }

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.record_delete_title)
                .setMessage(getString(R.string.record_delete_confirm, displayName))
                .setPositiveButton(R.string.record_delete_action, (ignored, button) -> {
                    try {
                        if (!RecordingStorage.deletePublished(this, uri, filePath)) {
                            throw new IOException("Recording was not deleted");
                        }
                        NotificationManagerCompat.from(this).cancel(notificationTag, NOTIFICATION_ID);
                        Toast.makeText(this, R.string.record_delete_success, Toast.LENGTH_SHORT).show();
                    } catch (final IOException e) {
                        Toast.makeText(this, R.string.record_delete_failed, Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, (ignored, button) -> finish())
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnCancelListener(ignored -> finish())
                .create();

        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }
}
