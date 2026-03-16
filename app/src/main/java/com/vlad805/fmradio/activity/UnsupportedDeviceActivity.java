package com.vlad805.fmradio.activity;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.vlad805.fmradio.R;

public class UnsupportedDeviceActivity extends AppCompatActivity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unsupported_device);
        setSupportActionBar(findViewById(R.id.unsupported_toolbar));
    }
}
