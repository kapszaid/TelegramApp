package com.nexgram.messenger.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.nexgram.messenger.R;

import java.io.File;
import java.util.Locale;

public class DataStorageActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "nexgram_data_storage_prefs";

    private Toolbar toolbar;
    private TextView tvCacheSize;
    private Button btnClearCache;

    private SwitchMaterial switchMobile;
    private SwitchMaterial switchWifi;
    private SwitchMaterial switchRoaming;
    private ProgressBar progressBar;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_data_storage);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        initViews();
        calculateAndDisplayCacheSize();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarDataStorage);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvCacheSize = findViewById(R.id.tvMediaCacheSize);
        btnClearCache = findViewById(R.id.btnClearMediaCache);

        switchMobile = findViewById(R.id.switchAutoDownloadMobile);
        switchWifi = findViewById(R.id.switchAutoDownloadWifi);
        switchRoaming = findViewById(R.id.switchAutoDownloadRoaming);
        progressBar = findViewById(R.id.pbDataStorage);

        switchMobile.setChecked(prefs.getBoolean("auto_download_mobile", true));
        switchWifi.setChecked(prefs.getBoolean("auto_download_wifi", true));
        switchRoaming.setChecked(prefs.getBoolean("auto_download_roaming", false));

        switchMobile.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("auto_download_mobile", c).apply());
        switchWifi.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("auto_download_wifi", c).apply());
        switchRoaming.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("auto_download_roaming", c).apply());

        btnClearCache.setOnClickListener(v -> confirmClearCache());
    }

    private void calculateAndDisplayCacheSize() {
        new Thread(() -> {
            long totalBytes = 0;
            File internalCache = getCacheDir();
            if (internalCache != null) {
                totalBytes += getDirSize(internalCache);
            }
            File externalCache = getExternalCacheDir();
            if (externalCache != null) {
                totalBytes += getDirSize(externalCache);
            }

            final String formatted = formatSize(totalBytes);
            runOnUiThread(() -> tvCacheSize.setText(formatted));
        }).start();
    }

    private long getDirSize(File dir) {
        long size = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            // Strictly exclude TDLib databases and encryption keys!
            if (f.getName().startsWith("tdlib_") || f.getName().endsWith(".bin") || f.getName().contains("keystore")) {
                continue;
            }
            if (f.isDirectory()) {
                size += getDirSize(f);
            } else {
                size += f.length();
            }
        }
        return size;
    }

    private void confirmClearCache() {
        new AlertDialog.Builder(this)
                .setTitle("Clear Media Cache?")
                .setMessage("All cached photos, videos and files will be removed from your device storage. They can be re-downloaded at any time from Telegram Cloud.")
                .setPositiveButton("Clear", (dialog, which) -> clearCacheInternal())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearCacheInternal() {
        new Thread(() -> {
            deleteCacheFiles(getCacheDir());
            File externalCache = getExternalCacheDir();
            if (externalCache != null) {
                deleteCacheFiles(externalCache);
            }

            runOnUiThread(() -> {
                Toast.makeText(this, "Media cache cleared", Toast.LENGTH_SHORT).show();
                calculateAndDisplayCacheSize();
            });
        }).start();
    }

    private void deleteCacheFiles(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            // Strictly protect databases and security files!
            if (f.getName().startsWith("tdlib_") || f.getName().endsWith(".bin") || f.getName().contains("keystore")) {
                continue;
            }
            if (f.isDirectory()) {
                deleteCacheFiles(f);
                f.delete();
            } else {
                f.delete();
            }
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
